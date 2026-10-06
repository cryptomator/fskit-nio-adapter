package org.cryptomator.frontend.fskit;

import org.cryptomator.frontend.fskit.fs.Errno;
import org.cryptomator.frontend.fskit.fs.HookedOperations;
import org.cryptomator.frontend.fskit.protocol.Frame;
import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.CloseRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.CloseResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.DirectoryEntry;
import org.cryptomator.frontend.fskit.protocol.Messages.Failure;
import org.cryptomator.frontend.fskit.protocol.Messages.GetattrRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.HelloRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.HelloResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.LookupRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.LookupResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.cryptomator.frontend.fskit.protocol.Messages.OpenRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.OpenResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.ReadRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ReadResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.ReaddirRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ReaddirResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.ReadlinkRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ReadlinkResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.RemoveRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.RemoveResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.RenameRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.RenameResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.StatfsRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.StatfsResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.SymlinkRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.SymlinkResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.SyncRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.WriteRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.WriteResponse;
import org.cryptomator.frontend.fskit.protocol.Opcode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Timeout(30)
public class BridgeSessionTest {

	private static final long ROOT = Messages.ROOT_NODE_ID;
	private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);
	/**
	 * For tests in which closing is meant to time out.
	 */
	private static final Duration SHORT_CLOSE_TIMEOUT = Duration.ofMillis(300);

	private Path root;
	private HookedOperations operations;
	private BridgeSession session;

	@BeforeEach
	public void setup(@TempDir Path tmpDir) throws IOException {
		root = tmpDir;
		operations = new HookedOperations(root, false);
		session = new BridgeSession(operations, CLOSE_TIMEOUT);
	}

	@AfterEach
	public void tearDown() throws IOException {
		session.close();
	}

	private void restartWithShortCloseTimeout() throws IOException {
		session.close();
		operations = new HookedOperations(root, false);
		session = new BridgeSession(operations, SHORT_CLOSE_TIMEOUT);
	}

	private TestBridgeClient connect() throws IOException {
		return TestBridgeClient.connect(session.port(), session.token());
	}

	private static <T> T ok(Object response, Class<T> type) {
		return Assertions.assertInstanceOf(type, response);
	}

	private static void assertClosedByServer(TestBridgeClient client) {
		Assertions.assertThrows(EOFException.class, client::receive);
	}

	@Test
	@DisplayName("completes the handshake with the session's token")
	public void testHandshake() throws IOException, InterruptedException {
		Assertions.assertFalse(session.awaitHandshake(Duration.ZERO));

		try (TestBridgeClient client = new TestBridgeClient(session.port())) {
			ok(client.request(new HelloRequest(Messages.MAGIC, Messages.PROTOCOL_VERSION, session.token())), HelloResponse.class);

			Assertions.assertTrue(session.awaitHandshake(Duration.ZERO));
			ok(client.request(new StatfsRequest()), StatfsResponse.class);
		}
	}

	@Test
	@DisplayName("rejects a wrong token and keeps listening")
	public void testWrongToken() throws IOException, InterruptedException {
		byte[] wrongToken = session.token();
		wrongToken[31] ^= 1;

		try (TestBridgeClient client = new TestBridgeClient(session.port())) {
			Assertions.assertEquals(new Failure(Errno.EACCES), client.request(new HelloRequest(Messages.MAGIC, Messages.PROTOCOL_VERSION, wrongToken)));
			assertClosedByServer(client);
		}

		Assertions.assertFalse(session.awaitHandshake(Duration.ZERO));
		try (TestBridgeClient _ = connect()) {
			Assertions.assertTrue(session.awaitHandshake(Duration.ZERO));
		}
	}

	@Test
	@DisplayName("keeps listening after a peer that leaves without a word or in the middle of a frame")
	public void testPeersThatLeave() throws IOException, InterruptedException {
		new TestBridgeClient(session.port()).close();
		try (TestBridgeClient client = new TestBridgeClient(session.port())) {
			// a frame header that announces more than follows
			client.send(ByteBuffer.wrap(new byte[]{0, 0, 0, 100, 0, 0, 1}));
		}

		Assertions.assertFalse(session.awaitHandshake(Duration.ZERO));
		try (TestBridgeClient client = connect()) {
			ok(client.request(new StatfsRequest()), StatfsResponse.class);
		}
	}

	@Test
	@DisplayName("every session has a random token of 32 bytes")
	public void testTokens(@TempDir Path otherRoot) throws IOException {
		try (BridgeSession other = new BridgeSession(new HookedOperations(otherRoot, false), CLOSE_TIMEOUT)) {
			Assertions.assertEquals(32, session.token().length);
			Assertions.assertFalse(Arrays.equals(new byte[32], session.token()));
			Assertions.assertFalse(Arrays.equals(session.token(), other.token()));
		}
	}

	@Test
	@DisplayName("rejects a token of the wrong length")
	public void testShortToken() throws IOException {
		try (TestBridgeClient client = new TestBridgeClient(session.port())) {
			Assertions.assertEquals(new Failure(Errno.EACCES), client.request(new HelloRequest(Messages.MAGIC, Messages.PROTOCOL_VERSION, new byte[0])));
			assertClosedByServer(client);
		}
	}

	@Test
	@DisplayName("rejects a different protocol version")
	public void testWrongVersion() throws IOException, InterruptedException {
		try (TestBridgeClient client = new TestBridgeClient(session.port())) {
			Assertions.assertEquals(new Failure(Errno.EPROTONOSUPPORT), client.request(new HelloRequest(Messages.MAGIC, Messages.PROTOCOL_VERSION + 1, session.token())));
			assertClosedByServer(client);
		}
		Assertions.assertFalse(session.awaitHandshake(Duration.ZERO));
	}

	@Test
	@DisplayName("rejects a wrong magic")
	public void testWrongMagic() throws IOException {
		try (TestBridgeClient client = new TestBridgeClient(session.port())) {
			Assertions.assertEquals(new Failure(Errno.EPROTONOSUPPORT), client.request(new HelloRequest(0x12345678, Messages.PROTOCOL_VERSION, session.token())));
			assertClosedByServer(client);
		}
	}

	@Test
	@DisplayName("rejects a hello that cannot be decoded")
	public void testUndecodableHello() throws IOException, InterruptedException {
		Frame valid = new HelloRequest(Messages.MAGIC, Messages.PROTOCOL_VERSION, session.token()).toFrame(1);
		List<Frame> undecodable = List.of( //
				new Frame(Frame.Kind.REQUEST, Opcode.HELLO, 1, ByteBuffer.allocate(3), ByteBuffer.allocate(0)), //
				new Frame(Frame.Kind.REQUEST, Opcode.HELLO, 1, valid.control(), ByteBuffer.allocate(1)));

		for (Frame hello : undecodable) {
			try (TestBridgeClient client = new TestBridgeClient(session.port())) {
				client.send(hello);
				Assertions.assertEquals(new Failure(Errno.EPROTONOSUPPORT), Messages.decodeResponse(client.receive()));
				assertClosedByServer(client);
			}
		}

		Assertions.assertFalse(session.awaitHandshake(Duration.ZERO));
		try (TestBridgeClient client = connect()) {
			ok(client.request(new StatfsRequest()), StatfsResponse.class);
		}
	}

	@Test
	@DisplayName("closes a connection whose first request is not a hello")
	public void testFirstRequestNotHello() throws IOException, InterruptedException {
		try (TestBridgeClient client = new TestBridgeClient(session.port())) {
			client.send(new StatfsRequest());
			assertClosedByServer(client);
		}
		Assertions.assertFalse(session.awaitHandshake(Duration.ZERO));
	}

	@Test
	@DisplayName("closes the connection on a frame that is not a request")
	public void testFrameThatIsNotARequest() throws IOException {
		try (TestBridgeClient client = connect()) {
			client.send(new Frame(Frame.Kind.RESPONSE, Opcode.STATFS, 7, ByteBuffer.allocate(4), ByteBuffer.allocate(0)));

			assertClosedByServer(client);
		}
	}

	@Test
	@DisplayName("closes the connection on a malformed request")
	public void testMalformedRequest() throws IOException {
		try (TestBridgeClient client = connect()) {
			client.send(new Frame(Frame.Kind.REQUEST, Opcode.GETATTR, 7, ByteBuffer.allocate(3), ByteBuffer.allocate(0)));

			assertClosedByServer(client);
		}
	}

	@Test
	@DisplayName("answers pipelined requests in order, echoing opcode and request id")
	public void testPipelinedRequests() throws IOException {
		try (TestBridgeClient client = connect()) {
			long first = client.send(new CreateRequest(ROOT, "file.txt", NodeType.FILE, 0644));
			long second = client.send(new LookupRequest(ROOT, "file.txt"));
			long third = client.send(new StatfsRequest());
			long fourth = client.send(new LookupRequest(ROOT, "missing.txt"));

			Frame created = client.receive();
			Frame found = client.receive();
			Frame statfs = client.receive();
			Frame missing = client.receive();

			Assertions.assertEquals(List.of(first, second, third, fourth), List.of(created.requestId(), found.requestId(), statfs.requestId(), missing.requestId()));
			Assertions.assertEquals(List.of(Opcode.CREATE, Opcode.LOOKUP, Opcode.STATFS, Opcode.LOOKUP), List.of(created.opcode(), found.opcode(), statfs.opcode(), missing.opcode()));
			long createdId = ok(Messages.decodeResponse(created), CreateResponse.class).attributes().nodeId();
			Assertions.assertEquals(createdId, ok(Messages.decodeResponse(found), LookupResponse.class).attributes().nodeId());
			Assertions.assertEquals(new Failure(Errno.ENOENT), Messages.decodeResponse(missing));
		}
	}

	@Test
	@DisplayName("serves a full sequence of operations")
	public void testOperationSequence() throws IOException {
		try (TestBridgeClient client = connect()) {
			long directory = ok(client.request(new CreateRequest(ROOT, "dir", NodeType.DIRECTORY, 0755)), CreateResponse.class).attributes().nodeId();
			long file = ok(client.request(new CreateRequest(directory, "file.txt", NodeType.FILE, 0644)), CreateResponse.class).attributes().nodeId();
			ok(client.request(new OpenRequest(file, Messages.MODE_READ | Messages.MODE_WRITE)), OpenResponse.class);
			Assertions.assertEquals(11, ok(client.request(new WriteRequest(file, 0, StandardCharsets.UTF_8.encode("hello world"))), WriteResponse.class).written());
			ReadResponse read = ok(client.request(new ReadRequest(file, 6, 100)), ReadResponse.class);
			Assertions.assertEquals("world", StandardCharsets.UTF_8.decode(read.data()).toString());
			ok(client.request(new CloseRequest(file, 0)), CloseResponse.class);
			Assertions.assertEquals("renamed.txt", ok(client.request(new RenameRequest(file, directory, ROOT, "renamed.txt")), RenameResponse.class).name());
			ReaddirResponse listing = ok(client.request(new ReaddirRequest(ROOT, 0, 0, false)), ReaddirResponse.class);
			Assertions.assertEquals(List.of(".", "..", "dir", "renamed.txt"), listing.entries().stream().map(DirectoryEntry::name).sorted().toList());
			Assertions.assertEquals("hello world", Files.readString(root.resolve("renamed.txt")));
			long link = ok(client.request(new SymlinkRequest(ROOT, "link", "renamed.txt")), SymlinkResponse.class).attributes().nodeId();
			Assertions.assertEquals("renamed.txt", ok(client.request(new ReadlinkRequest(link)), ReadlinkResponse.class).target());
			ok(client.request(new RemoveRequest(link, ROOT)), RemoveResponse.class);
			ok(client.request(new RemoveRequest(file, ROOT)), RemoveResponse.class);
			ok(client.request(new RemoveRequest(directory, ROOT)), RemoveResponse.class);
		}
		try (var children = Files.list(root)) {
			Assertions.assertEquals(0, children.count());
		}
	}

	@Test
	@DisplayName("two sessions serve their trees independently")
	public void testTwoSessions(@TempDir Path otherRoot) throws IOException {
		try (BridgeSession other = new BridgeSession(new HookedOperations(otherRoot, false), CLOSE_TIMEOUT); //
			 TestBridgeClient client = connect(); //
			 TestBridgeClient otherClient = TestBridgeClient.connect(other.port(), other.token())) {
			ok(client.request(new CreateRequest(ROOT, "here.txt", NodeType.FILE, 0644)), CreateResponse.class);
			ok(otherClient.request(new CreateRequest(ROOT, "there.txt", NodeType.FILE, 0644)), CreateResponse.class);

			Assertions.assertEquals(new Failure(Errno.ENOENT), client.request(new LookupRequest(ROOT, "there.txt")));
			Assertions.assertEquals(new Failure(Errno.ENOENT), otherClient.request(new LookupRequest(ROOT, "here.txt")));
			Assertions.assertTrue(Files.exists(root.resolve("here.txt")));
			Assertions.assertTrue(Files.exists(otherRoot.resolve("there.txt")));
		}
	}

	@Test
	@DisplayName("accepts no second connection after the handshake")
	public void testNoSecondConnection() throws IOException {
		try (TestBridgeClient _ = connect()) {
			Assertions.assertThrows(IOException.class, () -> new TestBridgeClient(session.port()));
		}
	}

	@Test
	@DisplayName("a dropped connection ends the session, which closes all channels and stops listening")
	public void testDroppedConnection() throws IOException, InterruptedException {
		try (TestBridgeClient client = connect()) {
			long file = ok(client.request(new CreateRequest(ROOT, "file.txt", NodeType.FILE, 0644)), CreateResponse.class).attributes().nodeId();
			ok(client.request(new OpenRequest(file, Messages.MODE_WRITE)), OpenResponse.class);
			Assertions.assertTrue(operations.openedChannels.getFirst().isOpen());
		}

		Assertions.assertTrue(operations.closed.await(10, TimeUnit.SECONDS));

		Assertions.assertFalse(operations.openedChannels.getFirst().isOpen());
		Assertions.assertThrows(IOException.class, () -> new TestBridgeClient(session.port()));
	}

	@Test
	@DisplayName("closing closes all channels")
	public void testClose() throws IOException {
		try (TestBridgeClient client = connect()) {
			long file = ok(client.request(new CreateRequest(ROOT, "file.txt", NodeType.FILE, 0644)), CreateResponse.class).attributes().nodeId();
			ok(client.request(new OpenRequest(file, Messages.MODE_WRITE)), OpenResponse.class);

			session.close();

			Assertions.assertFalse(operations.openedChannels.getFirst().isOpen());
			Assertions.assertThrows(IOException.class, () -> client.request(new SyncRequest()));
		}
	}

	@Test
	@DisplayName("closing before any connection ends the session")
	public void testCloseBeforeConnection() throws IOException {
		session.close();

		Assertions.assertThrows(IOException.class, () -> new TestBridgeClient(session.port()));
	}

	@Test
	@DisplayName("closing while the backend is blocked in a path operation fails until that operation returns")
	public void testCloseWhileBlockedInPathOperation() throws IOException, InterruptedException {
		restartWithShortCloseTimeout();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (TestBridgeClient client = connect()) {
			long file = ok(client.request(new CreateRequest(ROOT, "file.txt", NodeType.FILE, 0644)), CreateResponse.class).attributes().nodeId();
			ok(client.request(new OpenRequest(file, Messages.MODE_WRITE)), OpenResponse.class);
			operations.beforeReadingAttributes = HookedOperations.blocking(entered, release);
			client.send(new GetattrRequest(file));
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));

			Assertions.assertThrows(IOException.class, session::close);
			Assertions.assertTrue(operations.openedChannels.getFirst().isOpen());

			release.countDown();
			Assertions.assertTrue(operations.closed.await(10, TimeUnit.SECONDS));
			session.close();

			Assertions.assertFalse(operations.openedChannels.getFirst().isOpen());
		}
	}

	@Test
	@DisplayName("closing while the backend is blocked opening a channel closes that channel once it is open, without interrupting its flush")
	public void testCloseWhileBlockedOpeningChannel() throws IOException, InterruptedException {
		restartWithShortCloseTimeout();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (TestBridgeClient client = connect()) {
			Files.createFile(root.resolve("file.txt"));
			long file = ok(client.request(new LookupRequest(ROOT, "file.txt")), LookupResponse.class).attributes().nodeId();
			operations.beforeOpeningChannel = HookedOperations.blocking(entered, release);
			FileChannel buffering = Mockito.mock(FileChannel.class);
			operations.channelWrapper = channel -> writingOnClose(buffering, channel);
			client.send(new OpenRequest(file, Messages.MODE_WRITE));
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));

			Assertions.assertThrows(IOException.class, session::close);
			Assertions.assertEquals(List.of(), operations.openedChannels);

			release.countDown();
			Assertions.assertTrue(operations.closed.await(10, TimeUnit.SECONDS));
			session.close();

			Assertions.assertEquals(List.of(buffering), operations.openedChannels);
			Mockito.verify(buffering).close();
			Assertions.assertEquals("flushed", Files.readString(root.resolve("file.txt")));
		}
	}

	/**
	 * Stands in for a channel that buffers writes, as a vault's channels do: closing it writes, which an interrupted thread could not do.
	 */
	private static FileChannel writingOnClose(FileChannel buffering, FileChannel channel) {
		try {
			Mockito.doAnswer(_ -> {
				channel.write(StandardCharsets.UTF_8.encode("flushed"), 0);
				channel.close();
				return null;
			}).when(buffering).close();
			return buffering;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
