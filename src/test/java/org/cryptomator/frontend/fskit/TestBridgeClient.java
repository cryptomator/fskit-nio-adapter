package org.cryptomator.frontend.fskit;

import org.cryptomator.frontend.fskit.protocol.Frame;
import org.cryptomator.frontend.fskit.protocol.FrameCodec;
import org.cryptomator.frontend.fskit.protocol.Manifest;
import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.HelloRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.HelloResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.Request;
import org.cryptomator.frontend.fskit.protocol.Messages.Response;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * Plays the extension's part of the bridge protocol.
 */
public class TestBridgeClient implements Closeable {

	private final SocketChannel channel;
	private long nextRequestId = 1;

	public TestBridgeClient(int port) throws IOException {
		// the IPv4 loopback address, as the extension does
		this.channel = SocketChannel.open(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port));
	}

	/**
	 * Connects the way the extension does: to the session described by the manifest in the directory that is passed to {@code mount}.
	 */
	public static TestBridgeClient connect(Path rendezvousDir) throws IOException {
		Manifest manifest = Manifest.read(rendezvousDir);
		return connect(manifest.port(), manifest.token());
	}

	public static TestBridgeClient connect(int port, byte[] token) throws IOException {
		TestBridgeClient client = new TestBridgeClient(port);
		Response response = client.request(new HelloRequest(Messages.MAGIC, Messages.PROTOCOL_VERSION, token));
		if (!(response instanceof HelloResponse)) {
			client.close();
			throw new IOException("Handshake failed: " + response);
		}
		return client;
	}

	public Response request(Request request) throws IOException {
		long requestId = send(request);
		Frame frame = receive();
		if (frame.kind() != Frame.Kind.RESPONSE || frame.opcode() != request.opcode() || frame.requestId() != requestId) {
			throw new IOException("Response does not match request: " + frame);
		}
		return Messages.decodeResponse(frame);
	}

	/**
	 * Sends a request without waiting for its response.
	 *
	 * @return The request's id
	 */
	public long send(Request request) throws IOException {
		long requestId = nextRequestId++;
		send(request.toFrame(requestId));
		return requestId;
	}

	public void send(Frame frame) throws IOException {
		FrameCodec.write(channel, frame);
	}

	/**
	 * Sends bytes that need not form a frame.
	 */
	public void send(ByteBuffer bytes) throws IOException {
		while (bytes.hasRemaining()) {
			channel.write(bytes);
		}
	}

	public Frame receive() throws IOException {
		return FrameCodec.read(channel);
	}

	@Override
	public void close() throws IOException {
		channel.close();
	}
}
