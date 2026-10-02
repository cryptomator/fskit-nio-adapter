package org.cryptomator.frontend.fskit;

import org.cryptomator.frontend.fskit.fs.Errno;
import org.cryptomator.frontend.fskit.fs.FileSystemOperations;
import org.cryptomator.frontend.fskit.protocol.Frame;
import org.cryptomator.frontend.fskit.protocol.FrameCodec;
import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.Failure;
import org.cryptomator.frontend.fskit.protocol.Messages.HelloRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.HelloResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.Request;
import org.cryptomator.frontend.fskit.protocol.Messages.Response;
import org.cryptomator.frontend.fskit.protocol.Opcode;
import org.cryptomator.frontend.fskit.protocol.ProtocolException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Serves one mounted volume to the one extension process that presents this session's token.
 * <p>
 * A single thread reads a request, runs the operation and writes the response before it reads the next request. A slow backend call therefore stalls the volume, and no two operations ever interleave.
 */
public class BridgeSession implements Closeable {

	private static final Logger LOG = LoggerFactory.getLogger(BridgeSession.class);
	private static final int HANDSHAKE_READ_TIMEOUT_MILLIS = 5000;
	private static final byte[] IPV4_LOOPBACK = {127, 0, 0, 1};

	private final FileSystemOperations operations;
	private final Duration closeTimeout;
	private final byte[] token = new byte[Messages.TOKEN_LENGTH];
	private final ServerSocketChannel listener;
	private final CountDownLatch handshake = new CountDownLatch(1);
	private final CountDownLatch finished = new CountDownLatch(1);
	private volatile @Nullable SocketChannel connection;

	/**
	 * @param operations   The file system to serve. This session closes it when it ends.
	 * @param closeTimeout How long {@link #close()} waits for an operation in flight to return
	 */
	public BridgeSession(FileSystemOperations operations, Duration closeTimeout) throws IOException {
		this.operations = operations;
		this.closeTimeout = closeTimeout;
		new SecureRandom().nextBytes(token);
		this.listener = ServerSocketChannel.open();
		// the client connects to the IPv4 loopback address, whichever address family this JVM prefers
		listener.bind(new InetSocketAddress(InetAddress.getByAddress(IPV4_LOOPBACK), 0));
		Thread.ofPlatform().name("fskit-session-" + port()).daemon().start(this::run);
	}

	public int port() {
		return listener.socket().getLocalPort();
	}

	public byte[] token() {
		return token.clone();
	}

	/**
	 * @return {@code true} if the extension has connected and presented this session's token
	 */
	public boolean awaitHandshake(Duration timeout) throws InterruptedException {
		return handshake.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Ends this session: closes the listener and the connection and waits for the request thread to close all open channels.
	 * <p>
	 * The request thread is deliberately not interrupted: an interrupted thread cannot write, so it could not flush the channels it closes.
	 *
	 * @throws IOException If an operation is still running in the backend after the wait. The request thread cleans up as soon as that operation returns; calling this method again then succeeds.
	 */
	@Override
	public void close() throws IOException {
		listener.close();
		SocketChannel connected = connection;
		if (connected != null) {
			connected.close();
		}
		try {
			if (!finished.await(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IOException("An operation is still running in the backend. Cleanup is outstanding.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while waiting for the session to end.");
		}
	}

	private void run() {
		try (operations; listener; SocketChannel authenticated = accept()) {
			serve(authenticated);
		} catch (EOFException | ClosedChannelException e) {
			LOG.debug("Session ended.");
		} catch (IOException | RuntimeException e) {
			LOG.warn("Session ended unexpectedly.", e);
		} finally {
			finished.countDown();
		}
	}

	/**
	 * Accepts connections until one completes a valid {@code HELLO}, then stops listening.
	 */
	private SocketChannel accept() throws IOException {
		while (true) {
			SocketChannel candidate = listener.accept();
			connection = candidate;
			if (!listener.isOpen()) {
				// close() may have looked for a connection before this one was published
				candidate.close();
				throw new ClosedChannelException();
			}
			try {
				if (authenticate(candidate)) {
					return candidate;
				}
			} catch (IOException e) {
				LOG.debug("Rejected a connection that did not complete the handshake.", e);
			}
			connection = null;
			candidate.close();
		}
	}

	private boolean authenticate(SocketChannel candidate) throws IOException {
		// only the socket's stream honors a read timeout, which keeps a silent peer from blocking the real one
		candidate.socket().setSoTimeout(HANDSHAKE_READ_TIMEOUT_MILLIS);
		ReadableByteChannel timedReads = Channels.newChannel(candidate.socket().getInputStream());
		Frame frame = FrameCodec.read(timedReads);
		if (frame.kind() != Frame.Kind.REQUEST || frame.opcode() != Opcode.HELLO) {
			return false;
		}
		int status = helloStatus(frame);
		if (status == 0) {
			// by the time the client learns that it is connected, no second client can connect and awaitHandshake() reports the connection
			listener.close();
			handshake.countDown();
		}
		Response response = status == 0 ? new HelloResponse() : new Failure(status);
		FrameCodec.write(candidate, response.toFrame(Opcode.HELLO, frame.requestId()));
		return status == 0;
	}

	private int helloStatus(Frame frame) {
		try {
			HelloRequest hello = (HelloRequest) Messages.decodeRequest(frame);
			if (hello.magic() != Messages.MAGIC || hello.protocolVersion() != Messages.PROTOCOL_VERSION) {
				return Errno.EPROTONOSUPPORT;
			}
			return MessageDigest.isEqual(token, hello.token()) ? 0 : Errno.EACCES;
		} catch (ProtocolException e) {
			return Errno.EPROTONOSUPPORT;
		}
	}

	private void serve(SocketChannel channel) throws IOException {
		while (true) {
			Frame frame = FrameCodec.read(channel);
			Request request = Messages.decodeRequest(frame);
			Response response = operations.handle(request);
			LOG.trace("{} -> {}", request, response);
			FrameCodec.write(channel, response.toFrame(frame.opcode(), frame.requestId()));
		}
	}
}
