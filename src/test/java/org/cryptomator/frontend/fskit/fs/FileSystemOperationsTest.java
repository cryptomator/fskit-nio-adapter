package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.api.MasterkeyLoader;
import org.cryptomator.frontend.fskit.fs.HookedOperations.ChannelCall;
import org.cryptomator.frontend.fskit.protocol.Frame;
import org.cryptomator.frontend.fskit.protocol.FrameCodec;
import org.cryptomator.frontend.fskit.protocol.FrameCodecTest;
import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.Attributes;
import org.cryptomator.frontend.fskit.protocol.Messages.CloseRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.CloseResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.DirectoryEntry;
import org.cryptomator.frontend.fskit.protocol.Messages.Failure;
import org.cryptomator.frontend.fskit.protocol.Messages.ForgetRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ForgetResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.GetattrRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.GetattrResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.HelloRequest;
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
import org.cryptomator.frontend.fskit.protocol.Messages.Request;
import org.cryptomator.frontend.fskit.protocol.Messages.Response;
import org.cryptomator.frontend.fskit.protocol.Messages.SetattrRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.SetattrResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.StatfsRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.StatfsResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.SymlinkRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.SymlinkResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.SyncRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.SyncResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.Timestamp;
import org.cryptomator.frontend.fskit.protocol.Messages.WriteRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.WriteResponse;
import org.cryptomator.frontend.fskit.protocol.Opcode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.cryptomator.frontend.fskit.fs.HookedOperations.hooked;

@SuppressWarnings("OctalInteger")
public class FileSystemOperationsTest {

	private static final long ROOT = Messages.ROOT_NODE_ID;
	private static final int READ = Messages.MODE_READ;
	private static final int WRITE = Messages.MODE_WRITE;
	private static final Timestamp EPOCH = new Timestamp(0, 0);
	private static final String COMPOSED = "\u00e4.txt";
	private static final String DECOMPOSED = "a\u0308.txt";

	private Path root;
	private HookedOperations ops;

	@BeforeEach
	public void setup(@TempDir Path tmpDir) throws IOException {
		root = tmpDir;
		ops = new HookedOperations(root, false);
	}

	@AfterEach
	public void tearDown() {
		ops.close();
	}

	/* helpers */

	private <T extends Response> T ok(Request request, Class<T> type) {
		return Assertions.assertInstanceOf(type, ops.handle(request));
	}

	private void assertStatus(int status, Request request) {
		Assertions.assertEquals(new Failure(status), ops.handle(request), request.toString());
	}

	private Attributes lookup(long parentId, String name) {
		return ok(new LookupRequest(parentId, name), LookupResponse.class).attributes();
	}

	private Attributes create(long parentId, String name, NodeType type) {
		return ok(new CreateRequest(parentId, name, type, type == NodeType.DIRECTORY ? 0755 : 0644), CreateResponse.class).attributes();
	}

	private Attributes getattr(long nodeId) {
		return ok(new GetattrRequest(nodeId), GetattrResponse.class).attributes();
	}

	private WriteResponse write(long nodeId, long offset, String content) {
		return ok(new WriteRequest(nodeId, offset, StandardCharsets.UTF_8.encode(content)), WriteResponse.class);
	}

	private String read(long nodeId, long offset, int length) {
		return StandardCharsets.UTF_8.decode(ok(new ReadRequest(nodeId, offset, length), ReadResponse.class).data()).toString();
	}

	private ReaddirResponse readdir(long nodeId, long cookie, long verifier, boolean wantAttributes) {
		return ok(new ReaddirRequest(nodeId, cookie, verifier, wantAttributes), ReaddirResponse.class);
	}

	/**
	 * The attributes without their generation, which differs between two samples of the same state.
	 */
	private static Attributes state(Attributes attributes) {
		return new Attributes(attributes.type(), attributes.mode(), attributes.size(), attributes.nodeId(), attributes.parentId(), attributes.modified(), attributes.accessed(), attributes.created(), 0);
	}

	private List<String> names(ReaddirResponse page) {
		return page.entries().stream().map(DirectoryEntry::name).toList();
	}

	private Path backing(String name, String content) throws IOException {
		return Files.writeString(root.resolve(name), content);
	}

	private List<String> backingNames() throws IOException {
		return backingNames(root);
	}

	private static List<String> backingNames(Path directory) throws IOException {
		try (Stream<Path> children = Files.list(directory)) {
			return children.map(child -> child.getFileName().toString()).sorted().toList();
		}
	}

	/**
	 * Stands in for a channel that transfers less than it is asked to, as channels may.
	 */
	private static FileChannel oneBytePerCall(FileChannel channel) {
		try {
			FileChannel trickling = Mockito.mock(FileChannel.class);
			Mockito.when(trickling.read(Mockito.any(ByteBuffer.class), Mockito.anyLong())).thenAnswer(invocation -> {
				ByteBuffer buffer = invocation.getArgument(0);
				int read = channel.read(buffer.slice(buffer.position(), Math.min(1, buffer.remaining())), invocation.getArgument(1));
				buffer.position(buffer.position() + Math.max(0, read));
				return read;
			});
			Mockito.when(trickling.write(Mockito.any(ByteBuffer.class), Mockito.anyLong())).thenAnswer(invocation -> {
				ByteBuffer buffer = invocation.getArgument(0);
				int written = channel.write(buffer.slice(buffer.position(), Math.min(1, buffer.remaining())), invocation.getArgument(1));
				buffer.position(buffer.position() + written);
				return written;
			});
			Mockito.doAnswer(_ -> {
				channel.close();
				return null;
			}).when(trickling).close();
			return trickling;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Stream<Arguments> failures() {
		return Stream.of( //
				Arguments.of(new NoSuchFileException("x"), Errno.ENOENT), //
				Arguments.of(new FileAlreadyExistsException("x"), Errno.EEXIST), //
				Arguments.of(new DirectoryNotEmptyException("x"), Errno.ENOTEMPTY), //
				Arguments.of(new NotDirectoryException("x"), Errno.ENOTDIR), //
				Arguments.of(new AccessDeniedException("x"), Errno.EACCES), //
				Arguments.of(new ReadOnlyFileSystemException(), Errno.EROFS), //
				Arguments.of(new UnsupportedOperationException(), Errno.ENOTSUP), //
				Arguments.of(new InvalidPathException("x", "invalid"), Errno.EINVAL), //
				Arguments.of(new AtomicMoveNotSupportedException("x", "y", "across file stores"), Errno.EXDEV), //
				Arguments.of(new StatusException(Errno.EBUSY), Errno.EBUSY), //
				Arguments.of(new IOException("x"), Errno.EIO), //
				Arguments.of(new UncheckedIOException(new IOException("x")), Errno.EIO), //
				Arguments.of(new IllegalStateException("x"), Errno.EIO));
	}

	/* tests */

	@Nested
	@DisplayName("lookup and attributes")
	public class LookupAndAttributes {

		@Test
		@DisplayName("the root is directory 2 with parent 1")
		public void testRootAttributes() {
			Attributes attributes = getattr(ROOT);

			Assertions.assertEquals(NodeType.DIRECTORY, attributes.type());
			Assertions.assertEquals(2, attributes.nodeId());
			Assertions.assertEquals(1, attributes.parentId());
		}

		@Test
		@DisplayName("lookup reports the entry's attributes and stored name")
		public void testLookup() throws IOException {
			Path file = backing("file.txt", "hello");
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
			Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(1700000000, 123000000)));

			LookupResponse response = ok(new LookupRequest(ROOT, "file.txt"), LookupResponse.class);

			Assertions.assertEquals("file.txt", response.name());
			Assertions.assertEquals(NodeType.FILE, response.attributes().type());
			Assertions.assertEquals(0640, response.attributes().mode());
			Assertions.assertEquals(5, response.attributes().size());
			Assertions.assertEquals(64, response.attributes().nodeId());
			Assertions.assertEquals(ROOT, response.attributes().parentId());
			Assertions.assertEquals(new Timestamp(1700000000, 123000000), response.attributes().modified());
		}

		@Test
		@DisplayName("lookups of one entry yield one node")
		public void testLookupTwice() throws IOException {
			backing("file.txt", "");

			Assertions.assertEquals(lookup(ROOT, "file.txt").nodeId(), lookup(ROOT, "file.txt").nodeId());
		}

		@Test
		@DisplayName("lookup of a missing entry yields ENOENT")
		public void testLookupMissing() {
			assertStatus(Errno.ENOENT, new LookupRequest(ROOT, "missing"));
		}

		@Test
		@DisplayName("lookup in a file yields ENOTDIR")
		public void testLookupInFile() throws IOException {
			backing("file.txt", "");
			long file = lookup(ROOT, "file.txt").nodeId();

			assertStatus(Errno.ENOTDIR, new LookupRequest(file, "child"));
		}

		@ParameterizedTest(name = "\"{0}\"")
		@DisplayName("rejects invalid names")
		@ValueSource(strings = {"", ".", "..", "a/b", "/"})
		public void testInvalidNames(String name) throws IOException {
			backing("file.txt", "");
			long file = lookup(ROOT, "file.txt").nodeId();

			assertStatus(Errno.EINVAL, new LookupRequest(ROOT, name));
			assertStatus(Errno.EINVAL, new CreateRequest(ROOT, name, NodeType.FILE, 0644));
			assertStatus(Errno.EINVAL, new SymlinkRequest(ROOT, name, "target"));
			assertStatus(Errno.EINVAL, new RenameRequest(file, ROOT, "file.txt", ROOT, name));
			// a name that identifies an entry is only compared, and no entry has such a name
			assertStatus(Errno.ENOENT, new RemoveRequest(file, ROOT, name));
			assertStatus(Errno.ENOENT, new RenameRequest(file, ROOT, name, ROOT, "other.txt"));
			Assertions.assertEquals(List.of("file.txt"), backingNames());
		}

		@Test
		@DisplayName("an unknown node yields ESTALE")
		public void testUnknownNode() {
			assertStatus(Errno.ESTALE, new GetattrRequest(4711));
		}

		@Test
		@DisplayName("after a forget the node is gone and a new lookup gets a new id")
		public void testForget() throws IOException {
			backing("file.txt", "");
			long forgotten = lookup(ROOT, "file.txt").nodeId();

			ok(new ForgetRequest(forgotten, 1), ForgetResponse.class);

			assertStatus(Errno.ESTALE, new GetattrRequest(forgotten));
			Assertions.assertNotEquals(forgotten, lookup(ROOT, "file.txt").nodeId());
		}

		@Test
		@DisplayName("statfs reports the backing store")
		public void testStatfs() throws IOException {
			StatfsResponse response = ok(new StatfsRequest(), StatfsResponse.class);

			Assertions.assertEquals(Files.getFileStore(root).getTotalSpace(), response.totalBytes());
			Assertions.assertTrue(response.freeSpace().usableBytes() > 0);
		}

		@ParameterizedTest(name = "{0} -> {1}")
		@DisplayName("a failure of the backing file system becomes the matching status")
		@MethodSource("org.cryptomator.frontend.fskit.fs.FileSystemOperationsTest#failures")
		public void testFailures(Exception failure, int status) {
			ops.beforeReadingAttributes = _ -> {
				if (failure instanceof IOException e) {
					throw e;
				}
				throw (RuntimeException) failure;
			};

			assertStatus(status, new GetattrRequest(ROOT));
		}

		@Test
		@DisplayName("a second hello is rejected")
		public void testHello() {
			assertStatus(Errno.EINVAL, new HelloRequest(Messages.MAGIC, Messages.PROTOCOL_VERSION, new byte[Messages.TOKEN_LENGTH]));
		}
	}

	@Nested
	@DisplayName("names")
	public class Names {

		@Test
		@DisplayName("a decomposed name in the directory is listed and resolves under that spelling to one node")
		public void testDecomposedNameInDirectory() throws IOException {
			backing(DECOMPOSED, "");

			ReaddirResponse listing = readdir(ROOT, 0, 0, false);
			LookupResponse response = ok(new LookupRequest(ROOT, DECOMPOSED), LookupResponse.class);

			Assertions.assertEquals(List.of(".", "..", DECOMPOSED), names(listing));
			Assertions.assertEquals(DECOMPOSED, response.name());
			Assertions.assertEquals(listing.entries().get(2).nodeId(), response.attributes().nodeId());
		}

		@Test
		@DisplayName("an entry created through a decomposed name is stored composed and found under both spellings as one node")
		public void testCreateDecomposedName() throws IOException {
			CreateResponse created = ok(new CreateRequest(ROOT, DECOMPOSED, NodeType.FILE, 0644), CreateResponse.class);

			Assertions.assertEquals(COMPOSED, created.name());
			Assertions.assertEquals(List.of(COMPOSED), backingNames());
			Assertions.assertEquals(created.attributes().nodeId(), lookup(ROOT, DECOMPOSED).nodeId());
			Assertions.assertEquals(created.attributes().nodeId(), lookup(ROOT, COMPOSED).nodeId());
			Assertions.assertEquals(COMPOSED, ok(new LookupRequest(ROOT, DECOMPOSED), LookupResponse.class).name());
		}

		@Test
		@DisplayName("a link created through a decomposed name is stored composed, and the reply carries the stored name")
		public void testSymlinkDecomposedName() throws IOException {
			SymlinkResponse created = ok(new SymlinkRequest(ROOT, DECOMPOSED, "target"), SymlinkResponse.class);

			Assertions.assertEquals(COMPOSED, created.name());
			Assertions.assertEquals(List.of(COMPOSED), backingNames());
		}

		@Test
		@DisplayName("renaming onto a decomposed spelling of an existing composed name replaces that entry")
		public void testRenameOntoOtherSpelling() throws IOException {
			backing(COMPOSED, "old");
			backing("source", "new");
			long source = lookup(ROOT, "source").nodeId();

			RenameResponse response = ok(new RenameRequest(source, ROOT, "source", ROOT, DECOMPOSED), RenameResponse.class);

			Assertions.assertEquals(COMPOSED, response.name());
			Assertions.assertEquals(List.of(COMPOSED), backingNames());
			Assertions.assertEquals("new", Files.readString(root.resolve(COMPOSED)));
		}

		@ParameterizedTest(name = "stored composed: {0}")
		@DisplayName("a remove naming an entry in the other Unicode normalization form than the stored one removes it")
		@ValueSource(booleans = {true, false})
		public void testRemoveByOtherSpelling(boolean storedComposed) throws IOException {
			String stored = storedComposed ? COMPOSED : DECOMPOSED;
			backing(stored, "content");
			long file = lookup(ROOT, stored).nodeId();

			ok(new RemoveRequest(file, ROOT, storedComposed ? DECOMPOSED : COMPOSED), RemoveResponse.class);

			Assertions.assertEquals(List.of(), backingNames());
		}

		@ParameterizedTest(name = "stored composed: {0}")
		@DisplayName("a rename naming its source in the other Unicode normalization form than the stored one moves it")
		@ValueSource(booleans = {true, false})
		public void testRenameByOtherSpelling(boolean storedComposed) throws IOException {
			String stored = storedComposed ? COMPOSED : DECOMPOSED;
			backing(stored, "content");
			long file = lookup(ROOT, stored).nodeId();

			ok(new RenameRequest(file, ROOT, storedComposed ? DECOMPOSED : COMPOSED, ROOT, "renamed.txt"), RenameResponse.class);

			Assertions.assertEquals(List.of("renamed.txt"), backingNames());
		}
	}

	@Nested
	@DisplayName("below a directory that may be searched but not read")
	public class BelowUnreadableDirectory {

		private Path unreadable;
		private long trash;
		private long user;

		@BeforeEach
		public void setup() throws IOException {
			// like the .Trashes directory macOS creates in a volume's root
			trash = create(ROOT, "trash", NodeType.DIRECTORY).nodeId();
			unreadable = root.resolve("trash");
			Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("-wx--x--x"));
			user = create(trash, "501", NodeType.DIRECTORY).nodeId();
		}

		@AfterEach
		public void makeDeletable() throws IOException {
			Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rwx------"));
		}

		@Test
		@DisplayName("entries are created and found")
		public void testCreateAndLookup() {
			long file = create(user, "file.txt", NodeType.FILE).nodeId();

			Assertions.assertEquals(user, lookup(trash, "501").nodeId());
			Assertions.assertEquals(file, lookup(user, "file.txt").nodeId());
			Assertions.assertEquals("file.txt", ok(new LookupRequest(user, "file.txt"), LookupResponse.class).name());
			assertStatus(Errno.ENOENT, new LookupRequest(user, "missing.txt"));
		}

		@Test
		@DisplayName("an entry moved there is found as the same node")
		public void testRenameInto() throws IOException {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();

			ok(new RenameRequest(file, ROOT, "file.txt", user, "file.txt"), RenameResponse.class);

			Assertions.assertEquals(file, lookup(user, "file.txt").nodeId());
			Assertions.assertEquals("content", read(file, 0, 100));
			Assertions.assertEquals(List.of(".", "..", "file.txt"), names(readdir(user, 0, 0, false)));
		}

		@Test
		@DisplayName("both spellings of a name resolve to one node")
		public void testSpellings() {
			CreateResponse created = ok(new CreateRequest(user, DECOMPOSED, NodeType.FILE, 0644), CreateResponse.class);

			Assertions.assertEquals(COMPOSED, created.name());
			Assertions.assertEquals(created.attributes().nodeId(), lookup(user, DECOMPOSED).nodeId());
			Assertions.assertEquals(created.attributes().nodeId(), lookup(user, COMPOSED).nodeId());
			Assertions.assertEquals(COMPOSED, ok(new LookupRequest(user, DECOMPOSED), LookupResponse.class).name());
		}
	}

	@Nested
	@DisplayName("on a case-insensitive store")
	public class CaseInsensitiveStore {

		@BeforeEach
		public void assumeCaseInsensitiveStore() throws IOException {
			backing("File.txt", "original");
			Assumptions.assumeTrue(Files.exists(root.resolve("file.txt")), "store is case-sensitive");
		}

		@Test
		@DisplayName("lookup of a case variant yields ENOENT")
		public void testLookupCaseVariant() {
			assertStatus(Errno.ENOENT, new LookupRequest(ROOT, "file.txt"));
		}

		@Test
		@DisplayName("renaming an entry to a case variant of itself changes its case")
		public void testRenameToOwnCaseVariant() throws IOException {
			long file = lookup(ROOT, "File.txt").nodeId();

			RenameResponse response = ok(new RenameRequest(file, ROOT, "File.txt", ROOT, "file.txt"), RenameResponse.class);

			Assertions.assertEquals("file.txt", response.name());
			Assertions.assertEquals(backingNames(), List.of(response.name()));
			Assertions.assertEquals(file, lookup(ROOT, response.name()).nodeId());
		}

		@Test
		@DisplayName("renaming another entry to a case variant yields EEXIST and leaves the existing entry unchanged")
		public void testRenameToCaseVariantOfOtherEntry() throws IOException {
			backing("other.txt", "other");
			long other = lookup(ROOT, "other.txt").nodeId();

			assertStatus(Errno.EEXIST, new RenameRequest(other, ROOT, "other.txt", ROOT, "file.txt"));

			Assertions.assertEquals(List.of("File.txt", "other.txt"), backingNames());
			Assertions.assertEquals("original", Files.readString(root.resolve("File.txt")));
		}

		@Test
		@DisplayName("a case variant found while its directory could not be read is absent once the directory can be read")
		public void testCaseVariantAfterDirectoryBecomesReadable() throws IOException {
			Path dir = Files.createDirectory(root.resolve("dir"));
			Files.writeString(dir.resolve("Entry.txt"), "original");
			backing("other.txt", "other");
			long dirId = lookup(ROOT, "dir").nodeId();
			long other = lookup(ROOT, "other.txt").nodeId();
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("-wx--x--x"));
			boolean unreadable = !Files.isReadable(dir);
			Response whileUnreadable = ops.handle(new LookupRequest(dirId, "entry.txt"));
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
			Assumptions.assumeTrue(unreadable, "permissions do not apply to this user");
			// nothing could tell the stored spelling then, so the variant was found
			Assertions.assertEquals("entry.txt", Assertions.assertInstanceOf(LookupResponse.class, whileUnreadable).name());

			assertStatus(Errno.ENOENT, new LookupRequest(dirId, "entry.txt"));
			assertStatus(Errno.EEXIST, new RenameRequest(other, ROOT, "other.txt", dirId, "entry.txt"));
			Assertions.assertEquals("original", Files.readString(dir.resolve("Entry.txt")));
		}
	}

	@Nested
	@DisplayName("create, remove and rename")
	public class CreateRemoveRename {

		@Test
		@DisplayName("creates a file")
		public void testCreateFile() throws IOException {
			CreateResponse response = ok(new CreateRequest(ROOT, "new.txt", NodeType.FILE, 0600), CreateResponse.class);

			Assertions.assertTrue(Files.isRegularFile(root.resolve("new.txt")));
			Assertions.assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("new.txt"))));
			Assertions.assertEquals("new.txt", response.name());
			Assertions.assertEquals(NodeType.FILE, response.attributes().type());
			Assertions.assertEquals(0600, response.attributes().mode());
			Assertions.assertEquals(0, response.attributes().size());
			Assertions.assertEquals(ROOT, response.attributes().parentId());
			Assertions.assertEquals(ROOT, response.directoryAttributes().nodeId());
			Assertions.assertTrue(response.freeSpace().usableBytes() > 0);
			Assertions.assertEquals(response.attributes().nodeId(), lookup(ROOT, "new.txt").nodeId());
		}

		@Test
		@DisplayName("creates a directory")
		public void testCreateDirectory() {
			Attributes directory = create(ROOT, "dir", NodeType.DIRECTORY);
			Attributes child = create(directory.nodeId(), "child", NodeType.FILE);

			Assertions.assertEquals(NodeType.DIRECTORY, directory.type());
			Assertions.assertTrue(Files.isRegularFile(root.resolve("dir/child")));
			Assertions.assertEquals(directory.nodeId(), child.parentId());
		}

		@Test
		@DisplayName("a file created without write permission can be written by whoever created it, until it is closed")
		public void testCreateWithoutWritePermission() throws IOException {
			long file = ok(new CreateRequest(ROOT, "readonly.txt", NodeType.FILE, 0444), CreateResponse.class).attributes().nodeId();

			ok(new OpenRequest(file, WRITE), OpenResponse.class);
			Assertions.assertEquals(4, write(file, 0, "data").written());
			Assertions.assertEquals("data", read(file, 0, 100));
			Assertions.assertEquals(0444, getattr(file).mode());
			ok(new CloseRequest(file, 0), CloseResponse.class);

			Assertions.assertEquals("data", Files.readString(root.resolve("readonly.txt")));
			Assertions.assertEquals("r--r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("readonly.txt"))));
			Assertions.assertEquals(1, ops.openedChannels.size());
			Assertions.assertFalse(ops.openedChannels.getFirst().isOpen());
			Assertions.assertEquals("data", read(file, 0, 100));
		}

		@ParameterizedTest(name = "{0} with mode {1}")
		@DisplayName("creates an entry with exactly the requested mode, whatever the umask of this process")
		@CsvSource({"FILE, 0666, rw-rw-rw-", "DIRECTORY, 0777, rwxrwxrwx", "FILE, 0600, rw-------"})
		public void testCreateMode(NodeType type, int mode, String permissions) throws IOException {
			CreateResponse response = ok(new CreateRequest(ROOT, "created", type, mode), CreateResponse.class);

			Assertions.assertEquals(mode, response.attributes().mode());
			Assertions.assertEquals(permissions, PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("created"))));
		}

		@Test
		@DisplayName("a created file is at no moment more permissive than requested")
		public void testCreateModeFromTheStart() {
			List<String> permissionsWhenCreated = new ArrayList<>();
			ops.channelWrapper = channel -> {
				try {
					permissionsWhenCreated.add(PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("private.txt"))));
					return channel;
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			};

			ok(new CreateRequest(ROOT, "private.txt", NodeType.FILE, 0600), CreateResponse.class);

			Assertions.assertEquals(List.of("rw-------"), permissionsWhenCreated);
		}

		@Test
		@DisplayName("a create still succeeds when the permissions of the created entry cannot be set")
		public void testCreateWithRefusedPermissions() throws IOException {
			List<Path> refused = new ArrayList<>();
			ops.beforeSettingPermissions = path -> {
				refused.add(path);
				throw new UncheckedIOException(new AccessDeniedException(path.toString()));
			};

			CreateResponse response = ok(new CreateRequest(ROOT, "new.txt", NodeType.FILE, 0644), CreateResponse.class);

			Assertions.assertEquals(List.of(root.resolve("new.txt")), refused);
			Assertions.assertEquals("new.txt", response.name());
			Assertions.assertEquals(List.of("new.txt"), backingNames());
		}

		@Test
		@DisplayName("creating an existing entry yields EEXIST")
		public void testCreateExisting() throws IOException {
			backing("file.txt", "content");

			assertStatus(Errno.EEXIST, new CreateRequest(ROOT, "file.txt", NodeType.FILE, 0644));

			Assertions.assertEquals("content", Files.readString(root.resolve("file.txt")));
		}

		@Test
		@DisplayName("a create of type symlink yields ENOTSUP")
		public void testCreateUnsupportedType() throws IOException {
			assertStatus(Errno.ENOTSUP, new CreateRequest(ROOT, "link", NodeType.SYMLINK, 0644));

			Assertions.assertEquals(List.of(), backingNames());
		}

		@Test
		@DisplayName("removes a file and replies with its attributes from before the change")
		public void testRemoveFile() throws IOException {
			backing("file.txt", "12345");
			Attributes before = lookup(ROOT, "file.txt");

			RemoveResponse response = ok(new RemoveRequest(before.nodeId(), ROOT, "file.txt"), RemoveResponse.class);

			Assertions.assertEquals(List.of(), backingNames());
			Assertions.assertEquals(state(before), state(response.attributes()));
			Assertions.assertEquals(5, response.attributes().size());
			Assertions.assertEquals(ROOT, response.directoryAttributes().nodeId());
		}

		@Test
		@DisplayName("a remove replies with the attributes read just before it, not the ones last reported")
		public void testRemoveChangedFile() throws IOException {
			backing("file.txt", "12345");
			long file = lookup(ROOT, "file.txt").nodeId();
			backing("file.txt", "123456789");

			Assertions.assertEquals(9, ok(new RemoveRequest(file, ROOT, "file.txt"), RemoveResponse.class).attributes().size());
		}

		@Test
		@DisplayName("a remove or rename naming an entry by a name it no longer has yields ENOENT and changes nothing")
		public void testNameNoLongerHeld() throws IOException {
			backing("a.txt", "content");
			long file = lookup(ROOT, "a.txt").nodeId();
			ok(new RenameRequest(file, ROOT, "a.txt", ROOT, "b.txt"), RenameResponse.class);

			assertStatus(Errno.ENOENT, new RemoveRequest(file, ROOT, "a.txt"));
			assertStatus(Errno.ENOENT, new RenameRequest(file, ROOT, "a.txt", ROOT, "c.txt"));

			Assertions.assertEquals(List.of("b.txt"), backingNames());
		}
		@Test
		@DisplayName("removes an empty directory, but not one with entries")
		public void testRemoveDirectory() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			long child = create(directory, "child", NodeType.FILE).nodeId();

			assertStatus(Errno.ENOTEMPTY, new RemoveRequest(directory, ROOT, "dir"));
			ok(new RemoveRequest(child, directory, "child"), RemoveResponse.class);
			ok(new RemoveRequest(directory, ROOT, "dir"), RemoveResponse.class);

			Assertions.assertEquals(List.of(), backingNames());
		}

		@Test
		@DisplayName("a file re-created under a removed item's name is a new node")
		public void testRecreateRemovedName() throws IOException {
			backing("file.txt", "old");
			long removed = lookup(ROOT, "file.txt").nodeId();
			ok(new RemoveRequest(removed, ROOT, "file.txt"), RemoveResponse.class);

			long recreated = create(ROOT, "file.txt", NodeType.FILE).nodeId();

			Assertions.assertNotEquals(removed, recreated);
			Assertions.assertEquals(recreated, lookup(ROOT, "file.txt").nodeId());
		}

		@Test
		@DisplayName("renames a file within its directory")
		public void testRename() throws IOException {
			backing("old.txt", "content");
			Attributes file = lookup(ROOT, "old.txt");

			RenameResponse response = ok(new RenameRequest(file.nodeId(), ROOT, "old.txt", ROOT, "new.txt"), RenameResponse.class);

			Assertions.assertEquals(List.of("new.txt"), backingNames());
			Assertions.assertEquals("new.txt", response.name());
			Assertions.assertEquals(file.nodeId(), response.attributes().nodeId());
			Assertions.assertNull(response.replacedAttributes());
			Assertions.assertEquals(file.nodeId(), lookup(ROOT, "new.txt").nodeId());
			assertStatus(Errno.ENOENT, new LookupRequest(ROOT, "old.txt"));
		}

		@Test
		@DisplayName("moving a directory re-paths the open file inside it")
		public void testMoveDirectoryWithOpenFile() throws IOException {
			long source = create(ROOT, "source", NodeType.DIRECTORY).nodeId();
			long target = create(ROOT, "target", NodeType.DIRECTORY).nodeId();
			long file = create(source, "file.txt", NodeType.FILE).nodeId();
			write(file, 0, "before");

			RenameResponse response = ok(new RenameRequest(source, ROOT, "source", target, "moved"), RenameResponse.class);
			write(file, 6, " and after");
			ok(new CloseRequest(file, 0), CloseResponse.class);

			Assertions.assertEquals(target, response.attributes().parentId());
			Assertions.assertEquals(target, response.destinationDirectoryAttributes().nodeId());
			Assertions.assertEquals(ROOT, response.sourceDirectoryAttributes().nodeId());
			Assertions.assertEquals("before and after", Files.readString(root.resolve("target/moved/file.txt")));
			Assertions.assertEquals(file, lookup(source, "file.txt").nodeId());
			Assertions.assertEquals(16, getattr(file).size());
		}

		@Test
		@DisplayName("a replacing rename replies with the replaced item's attributes from before the change and unlinks it")
		public void testRenameReplacing() throws IOException {
			backing("source.txt", "new");
			backing("target.txt", "replaced");
			long source = lookup(ROOT, "source.txt").nodeId();
			Attributes target = lookup(ROOT, "target.txt");

			RenameResponse response = ok(new RenameRequest(source, ROOT, "source.txt", ROOT, "target.txt"), RenameResponse.class);

			Assertions.assertEquals(List.of("target.txt"), backingNames());
			Assertions.assertEquals("new", Files.readString(root.resolve("target.txt")));
			Assertions.assertEquals("target.txt", response.name());
			Assertions.assertEquals(state(target), state(response.replacedAttributes()));
			Assertions.assertEquals(8, response.replacedAttributes().size());
			assertStatus(Errno.ESTALE, new RemoveRequest(target.nodeId(), ROOT, "target.txt"));
			Assertions.assertEquals(state(target), state(getattr(target.nodeId())));
			Assertions.assertEquals(source, lookup(ROOT, "target.txt").nodeId());
		}

		@Test
		@DisplayName("a replacing rename replies with the replaced item's attributes read just before it, not the ones last reported")
		public void testRenameReplacingChangedFile() throws IOException {
			backing("source.txt", "new");
			backing("target.txt", "replaced");
			long source = lookup(ROOT, "source.txt").nodeId();
			lookup(ROOT, "target.txt");
			backing("target.txt", "replaced and grown");

			RenameResponse response = ok(new RenameRequest(source, ROOT, "source.txt", ROOT, "target.txt"), RenameResponse.class);

			Assertions.assertEquals(18, response.replacedAttributes().size());
		}

		@Test
		@DisplayName("a replacing rename that fails leaves the file it would have replaced in place")
		public void testFailingRenameKeepsTarget() throws IOException {
			long locked = create(ROOT, "locked", NodeType.DIRECTORY).nodeId();
			long source = create(locked, "source.txt", NodeType.FILE).nodeId();
			backing("target.txt", "replaced");
			long target = lookup(ROOT, "target.txt").nodeId();
			// entries cannot be moved out of a directory that is not writable
			Files.setPosixFilePermissions(root.resolve("locked"), PosixFilePermissions.fromString("r-xr-xr-x"));
			try {
				Assumptions.assumeFalse(Files.isWritable(root.resolve("locked")), "permissions do not apply to this user");

				assertStatus(Errno.EACCES, new RenameRequest(source, locked, "source.txt", ROOT, "target.txt"));

				Assertions.assertEquals("replaced", Files.readString(root.resolve("target.txt")));
				Assertions.assertEquals(target, lookup(ROOT, "target.txt").nodeId());
				Assertions.assertEquals(source, lookup(locked, "source.txt").nodeId());
			} finally {
				Files.setPosixFilePermissions(root.resolve("locked"), PosixFilePermissions.fromString("rwxr-xr-x"));
			}
		}

		@Test
		@DisplayName("a file that cannot replace a file atomically yields EXDEV and leaves both in place")
		public void testRenameWithoutAtomicMove() throws IOException {
			backing("source.txt", "new");
			backing("target.txt", "replaced");
			long source = lookup(ROOT, "source.txt").nodeId();
			long target = lookup(ROOT, "target.txt").nodeId();
			ops.beforeMoving = path -> {
				throw new AtomicMoveNotSupportedException(path.toString(), "target.txt", "across file stores");
			};

			assertStatus(Errno.EXDEV, new RenameRequest(source, ROOT, "source.txt", ROOT, "target.txt"));

			Assertions.assertEquals(List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING), ops.moveOptions);
			Assertions.assertEquals("replaced", Files.readString(root.resolve("target.txt")));
			Assertions.assertEquals("new", Files.readString(root.resolve("source.txt")));
			Assertions.assertEquals(target, lookup(ROOT, "target.txt").nodeId());
			Assertions.assertEquals(source, lookup(ROOT, "source.txt").nodeId());
		}

		@Test
		@DisplayName("an entry that cannot be moved atomically yields EXDEV and stays where it is")
		public void testMoveWithoutAtomicMove() throws IOException {
			backing("source.txt", "content");
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			long source = lookup(ROOT, "source.txt").nodeId();
			ok(new OpenRequest(source, READ | WRITE), OpenResponse.class);
			ops.beforeMoving = path -> {
				throw new AtomicMoveNotSupportedException(path.toString(), "dir/moved.txt", "across file stores");
			};

			assertStatus(Errno.EXDEV, new RenameRequest(source, ROOT, "source.txt", directory, "moved.txt"));

			Assertions.assertEquals(List.of(StandardCopyOption.ATOMIC_MOVE), ops.moveOptions);
			Assertions.assertEquals(source, lookup(ROOT, "source.txt").nodeId());
			write(source, 7, " kept");
			Assertions.assertEquals("content kept", Files.readString(root.resolve("source.txt")));
		}

		@Test
		@DisplayName("renames a directory that lacks write permission")
		public void testRenameReadOnlyDirectory() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			long child = create(directory, "child", NodeType.FILE).nodeId();
			ok(new CloseRequest(child, 0), CloseResponse.class);
			ok(new SetattrRequest(directory, Messages.ATTRIBUTE_MODE, 0, 0555, EPOCH, EPOCH), SetattrResponse.class);
			try {
				ok(new RenameRequest(directory, ROOT, "dir", ROOT, "renamed"), RenameResponse.class);

				Assertions.assertEquals(List.of("renamed"), backingNames());
				Assertions.assertEquals(child, lookup(directory, "child").nodeId());
			} finally {
				ok(new SetattrRequest(directory, Messages.ATTRIBUTE_MODE, 0, 0755, EPOCH, EPOCH), SetattrResponse.class);
			}
		}

		@Test
		@DisplayName("renaming an entry onto its own name changes nothing")
		public void testRenameOntoOwnName() throws IOException {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();

			RenameResponse response = ok(new RenameRequest(file, ROOT, "file.txt", ROOT, "file.txt"), RenameResponse.class);

			Assertions.assertEquals("file.txt", response.name());
			Assertions.assertNull(response.replacedAttributes());
			Assertions.assertEquals("content", Files.readString(root.resolve("file.txt")));
			Assertions.assertEquals(file, lookup(ROOT, "file.txt").nodeId());
			ok(new RemoveRequest(file, ROOT, "file.txt"), RemoveResponse.class);
		}

		@Test
		@DisplayName("a directory replaces an empty directory")
		public void testRenameOverEmptyDirectory() {
			long source = create(ROOT, "source", NodeType.DIRECTORY).nodeId();
			long child = create(source, "child", NodeType.FILE).nodeId();
			Attributes target = create(ROOT, "target", NodeType.DIRECTORY);

			RenameResponse response = ok(new RenameRequest(source, ROOT, "source", ROOT, "target"), RenameResponse.class);

			Assertions.assertEquals(target.nodeId(), response.replacedAttributes().nodeId());
			Assertions.assertEquals(source, lookup(ROOT, "target").nodeId());
			Assertions.assertEquals(child, lookup(source, "child").nodeId());
			Assertions.assertFalse(Files.exists(root.resolve("source")));
		}

		@Test
		@DisplayName("moving a directory into itself or below itself yields EINVAL and moves nothing")
		public void testMoveDirectoryBelowItself() throws IOException {
			long outer = create(ROOT, "outer", NodeType.DIRECTORY).nodeId();
			long inner = create(outer, "inner", NodeType.DIRECTORY).nodeId();
			long innermost = create(inner, "innermost", NodeType.DIRECTORY).nodeId();

			assertStatus(Errno.EINVAL, new RenameRequest(outer, ROOT, "outer", outer, "moved"));
			assertStatus(Errno.EINVAL, new RenameRequest(outer, ROOT, "outer", innermost, "moved"));

			Assertions.assertEquals(List.of(), ops.moveOptions);
			Assertions.assertEquals(List.of("outer"), backingNames());
			Assertions.assertEquals(innermost, lookup(inner, "innermost").nodeId());
			ok(new RenameRequest(innermost, inner, "innermost", ROOT, "moved up"), RenameResponse.class);
			Assertions.assertEquals(List.of("moved up", "outer"), backingNames());
		}

		@Test
		@DisplayName("renaming a directory over a directory with entries yields ENOTEMPTY")
		public void testRenameOverNonEmptyDirectory() {
			long source = create(ROOT, "source", NodeType.DIRECTORY).nodeId();
			long target = create(ROOT, "target", NodeType.DIRECTORY).nodeId();
			create(target, "child", NodeType.FILE);

			assertStatus(Errno.ENOTEMPTY, new RenameRequest(source, ROOT, "source", ROOT, "target"));

			Assertions.assertTrue(Files.isDirectory(root.resolve("source")));
			Assertions.assertEquals(target, lookup(ROOT, "target").nodeId());
		}
	}

	@Nested
	@DisplayName("a removed file that is still open")
	public class RemovedButOpen {

		private long file;

		@BeforeEach
		public void setup() throws IOException {
			backing("file.txt", "0123456789");
			file = lookup(ROOT, "file.txt").nodeId();
			ok(new OpenRequest(file, READ | WRITE), OpenResponse.class);
			ok(new RemoveRequest(file, ROOT, "file.txt"), RemoveResponse.class);
		}

		@Test
		@DisplayName("stays readable and writable and reports its grown size")
		public void testReadWrite() throws IOException {
			Assertions.assertEquals("0123456789", read(file, 0, 100));

			WriteResponse written = write(file, 10, "abcdef");

			Assertions.assertEquals(16, written.attributes().size());
			Assertions.assertEquals(file, written.attributes().nodeId());
			Assertions.assertEquals("0123456789abcdef", read(file, 0, 100));
			Assertions.assertEquals(16, getattr(file).size());
			Assertions.assertEquals(16, ok(new ReadRequest(file, 0, 1), ReadResponse.class).attributes().size());
			Assertions.assertEquals(List.of(), backingNames());
		}

		@Test
		@DisplayName("replies with a later generation after each change")
		public void testGenerations() {
			long before = getattr(file).generation();

			Assertions.assertTrue(write(file, 10, "abcdef").attributes().generation() > before);
		}

		@Test
		@DisplayName("can be truncated and extended through its writable channel")
		public void testTruncate() {
			SetattrResponse truncated = ok(new SetattrRequest(file, Messages.ATTRIBUTE_SIZE, 4, 0, EPOCH, EPOCH), SetattrResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_SIZE, truncated.applied());
			Assertions.assertEquals(4, truncated.attributes().size());
			Assertions.assertEquals("0123", read(file, 0, 100));
			Assertions.assertEquals(8, ok(new SetattrRequest(file, Messages.ATTRIBUTE_SIZE, 8, 0, EPOCH, EPOCH), SetattrResponse.class).attributes().size());
			Assertions.assertEquals("0123\0\0\0\0", read(file, 0, 100));
			Assertions.assertEquals(1, ops.openedChannels.size());
		}

		@Test
		@DisplayName("a time or permission change leaves a file re-created under its name untouched")
		public void testTimesAndModeNotApplied() throws IOException {
			Path recreated = backing("file.txt", "recreated");
			Files.setPosixFilePermissions(recreated, PosixFilePermissions.fromString("rw-r--r--"));
			FileTime modified = Files.getLastModifiedTime(recreated);
			Attributes before = getattr(file);
			int valid = Messages.ATTRIBUTE_SIZE | Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_ACCESSED | Messages.ATTRIBUTE_MODIFIED;

			SetattrResponse response = ok(new SetattrRequest(file, valid, 2, 0600, EPOCH, EPOCH), SetattrResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_SIZE, response.applied());
			Assertions.assertEquals(before.mode(), response.attributes().mode());
			Assertions.assertEquals(before.modified(), response.attributes().modified());
			Assertions.assertEquals("recreated", Files.readString(recreated));
			Assertions.assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(recreated)));
			Assertions.assertEquals(modified, Files.getLastModifiedTime(recreated));
			Assertions.assertEquals("01", read(file, 0, 100));
		}

		@Test
		@DisplayName("sync includes its channel")
		public void testSync() throws IOException {
			FileChannel channel = Mockito.mock(FileChannel.class);
			ops.channelWrapper = _ -> channel;
			backing("other.txt", "");
			long other = lookup(ROOT, "other.txt").nodeId();
			ok(new OpenRequest(other, WRITE), OpenResponse.class);
			ok(new RemoveRequest(other, ROOT, "other.txt"), RemoveResponse.class);

			ok(new SyncRequest(), SyncResponse.class);

			Mockito.verify(channel).force(false);
		}

		@Test
		@DisplayName("is unreachable by path: it cannot be removed or renamed again")
		public void testNoPathOperations() throws IOException {
			backing("file.txt", "recreated");

			assertStatus(Errno.ESTALE, new RemoveRequest(file, ROOT, "file.txt"));
			assertStatus(Errno.ESTALE, new RenameRequest(file, ROOT, "file.txt", ROOT, "other.txt"));

			Assertions.assertEquals(List.of("file.txt"), backingNames());
			Assertions.assertNotEquals(file, lookup(ROOT, "file.txt").nodeId());
		}

		@Test
		@DisplayName("is gone for good once it is closed and forgotten")
		public void testCloseAndForget() {
			ok(new CloseRequest(file, 0), CloseResponse.class);
			Assertions.assertFalse(ops.openedChannels.getFirst().isOpen());

			assertStatus(Errno.EIO, new ReadRequest(file, 0, 10));
			assertStatus(Errno.EIO, new OpenRequest(file, READ));
			Assertions.assertEquals(10, getattr(file).size());
			ok(new ForgetRequest(file, 1), ForgetResponse.class);
			assertStatus(Errno.ESTALE, new GetattrRequest(file));
		}
	}

	@Nested
	@DisplayName("a removed item without a writable channel")
	public class RemovedWithoutWritableChannel {

		@Test
		@DisplayName("a file open for reading cannot be widened, written or truncated")
		public void testReadOnlyChannel() throws IOException {
			backing("file.txt", "0123456789");
			long file = lookup(ROOT, "file.txt").nodeId();
			ok(new OpenRequest(file, READ), OpenResponse.class);
			ok(new RemoveRequest(file, ROOT, "file.txt"), RemoveResponse.class);

			Assertions.assertEquals("0123456789", read(file, 0, 100));
			assertStatus(Errno.EIO, new OpenRequest(file, READ | WRITE));
			assertStatus(Errno.EIO, new WriteRequest(file, 0, ByteBuffer.allocate(1)));
			assertStatus(Errno.EINVAL, new SetattrRequest(file, Messages.ATTRIBUTE_SIZE, 0, 0, EPOCH, EPOCH));
			Assertions.assertEquals(1, ops.openedChannels.size());
		}

		@Test
		@DisplayName("a file that was never opened cannot be opened anymore")
		public void testNeverOpened() throws IOException {
			backing("file.txt", "0123456789");
			long file = lookup(ROOT, "file.txt").nodeId();
			ok(new RemoveRequest(file, ROOT, "file.txt"), RemoveResponse.class);
			backing("file.txt", "recreated");

			assertStatus(Errno.EIO, new OpenRequest(file, READ));
			assertStatus(Errno.EIO, new ReadRequest(file, 0, 10));
			assertStatus(Errno.EIO, new WriteRequest(file, 0, ByteBuffer.allocate(1)));
			Assertions.assertEquals(10, getattr(file).size());
			Assertions.assertEquals("recreated", Files.readString(root.resolve("file.txt")));
		}

		@Test
		@DisplayName("a removed directory answers nothing but getattr")
		public void testRemovedDirectory() {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			ok(new RemoveRequest(directory, ROOT, "dir"), RemoveResponse.class);
			create(ROOT, "dir", NodeType.DIRECTORY);

			assertStatus(Errno.ESTALE, new LookupRequest(directory, "child"));
			assertStatus(Errno.ESTALE, new ReaddirRequest(directory, 0, 0, false));
			assertStatus(Errno.ESTALE, new CreateRequest(directory, "child", NodeType.FILE, 0644));
			Assertions.assertEquals(NodeType.DIRECTORY, getattr(directory).type());
			Assertions.assertFalse(Files.exists(root.resolve("dir/child")));
		}

		@Test
		@DisplayName("a file replaced by a rename keeps serving its content through its open channel")
		public void testReplacedByRename() throws IOException {
			backing("target.txt", "old content");
			backing("source.txt", "new");
			long target = lookup(ROOT, "target.txt").nodeId();
			long source = lookup(ROOT, "source.txt").nodeId();
			ok(new OpenRequest(target, READ | WRITE), OpenResponse.class);

			ok(new RenameRequest(source, ROOT, "source.txt", ROOT, "target.txt"), RenameResponse.class);
			write(target, 0, "OLD");

			Assertions.assertEquals("OLD content", read(target, 0, 100));
			Assertions.assertEquals(11, getattr(target).size());
			Assertions.assertEquals("new", read(source, 0, 100));
			Assertions.assertEquals("new", Files.readString(root.resolve("target.txt")));
		}
	}

	@Nested
	@DisplayName("on a vault")
	public class OnVault {

		private FileSystem vault;

		@BeforeEach
		public void setup() throws IOException {
			MasterkeyLoader keyLoader = _ -> new Masterkey(new byte[64]);
			CryptoFileSystemProperties properties = CryptoFileSystemProperties.cryptoFileSystemProperties().withKeyLoader(keyLoader).build();
			CryptoFileSystemProvider.initialize(root, properties, URI.create("test:key"));
			vault = CryptoFileSystemProvider.newFileSystem(root, properties);
			ops.close();
			ops = new HookedOperations(vault.getPath("/"), false);
		}

		@AfterEach
		public void tearDown() throws IOException {
			ops.close();
			vault.close();
		}

		private long createFile(String name, String content) {
			long file = create(ROOT, name, NodeType.FILE).nodeId();
			write(file, 0, content);
			return file;
		}

		@Test
		@DisplayName("replacing a file that is open yields EBUSY")
		public void testRenameOverOpenFile() {
			long source = createFile("source.txt", "new");
			long target = createFile("target.txt", "replaced");
			ok(new CloseRequest(source, 0), CloseResponse.class);

			assertStatus(Errno.EBUSY, new RenameRequest(source, ROOT, "source.txt", ROOT, "target.txt"));

			ReadResponse response = ok(new ReadRequest(target, 0, 100), ReadResponse.class);
			Assertions.assertEquals("replaced", StandardCharsets.UTF_8.decode(response.data()).toString());
			Assertions.assertEquals(8, response.attributes().size());
		}

		@Test
		@DisplayName("a file replaces a file that is closed")
		public void testRenameOverClosedFile() {
			long source = createFile("source.txt", "new");
			long target = createFile("target.txt", "replaced");
			ok(new CloseRequest(source, 0), CloseResponse.class);
			ok(new CloseRequest(target, 0), CloseResponse.class);

			RenameResponse response = ok(new RenameRequest(source, ROOT, "source.txt", ROOT, "target.txt"), RenameResponse.class);

			Assertions.assertEquals(target, response.replacedAttributes().nodeId());
			Assertions.assertEquals("new", read(source, 0, 100));
			Assertions.assertEquals(List.of(".", "..", "target.txt"), names(readdir(ROOT, 0, 0, false)));
		}

		@Test
		@DisplayName("a directory replaces an empty directory")
		public void testRenameOverEmptyDirectory() {
			long source = create(ROOT, "source", NodeType.DIRECTORY).nodeId();
			long child = createFile("child.txt", "content");
			ok(new CloseRequest(child, 0), CloseResponse.class);
			ok(new RenameRequest(child, ROOT, "child.txt", source, "child.txt"), RenameResponse.class);
			create(ROOT, "target", NodeType.DIRECTORY);

			ok(new RenameRequest(source, ROOT, "source", ROOT, "target"), RenameResponse.class);

			Assertions.assertEquals(List.of(".", "..", "target"), names(readdir(ROOT, 0, 0, false)));
			Assertions.assertEquals("content", read(lookup(source, "child.txt").nodeId(), 0, 100));
		}

		@Test
		@DisplayName("a directory replaces a directory that holds only hidden files")
		public void testRenameOverDirectoryWithHiddenFiles() throws IOException {
			long source = create(ROOT, "source", NodeType.DIRECTORY).nodeId();
			Files.writeString(vault.getPath("/source/content.txt"), "content");
			create(ROOT, "target", NodeType.DIRECTORY);
			Files.writeString(vault.getPath("/target/._a"), "");

			ok(new RenameRequest(source, ROOT, "source", ROOT, "target"), RenameResponse.class);

			Assertions.assertEquals(List.of(".", "..", "target"), names(readdir(ROOT, 0, 0, false)));
			Assertions.assertEquals(List.of("content.txt"), backingNames(vault.getPath("/target")));
		}

		@Test
		@DisplayName("removing a directory that holds only hidden files removes them with it")
		public void testRemoveDirectoryWithHiddenFiles() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			Files.writeString(vault.getPath("/dir/._a"), "");
			Files.writeString(vault.getPath("/dir/.DS_Store"), "");

			ok(new RemoveRequest(directory, ROOT, "dir"), RemoveResponse.class);

			Assertions.assertEquals(List.of(), backingNames(vault.getPath("/")));
		}

		@Test
		@DisplayName("removing a directory that also holds a visible entry yields ENOTEMPTY and deletes nothing")
		public void testRemoveDirectoryWithVisibleEntry() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			Files.writeString(vault.getPath("/dir/._a"), "");
			Files.writeString(vault.getPath("/dir/visible.txt"), "");

			assertStatus(Errno.ENOTEMPTY, new RemoveRequest(directory, ROOT, "dir"));

			Assertions.assertEquals(List.of("._a", "visible.txt"), backingNames(vault.getPath("/dir")));
		}

		@Test
		@DisplayName("removing a directory that holds a hidden directory yields ENOTEMPTY and deletes nothing")
		public void testRemoveDirectoryWithHiddenDirectory() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			Files.writeString(vault.getPath("/dir/.DS_Store"), "");
			Files.createDirectory(vault.getPath("/dir/._d"));

			assertStatus(Errno.ENOTEMPTY, new RemoveRequest(directory, ROOT, "dir"));

			Assertions.assertEquals(List.of(".DS_Store", "._d"), backingNames(vault.getPath("/dir")));
		}

		@Test
		@DisplayName("a file does not replace a directory that holds only hidden files, which keeps them")
		public void testRenameFileOverDirectoryWithHiddenFiles() throws IOException {
			long file = createFile("file.txt", "content");
			ok(new CloseRequest(file, 0), CloseResponse.class);
			create(ROOT, "target", NodeType.DIRECTORY);
			Files.writeString(vault.getPath("/target/.DS_Store"), "");

			assertStatus(Errno.ENOTEMPTY, new RenameRequest(file, ROOT, "file.txt", ROOT, "target"));

			Assertions.assertEquals(List.of(".", "..", "file.txt", "target"), names(readdir(ROOT, 0, 0, false)).stream().sorted().toList());
			Assertions.assertEquals(List.of(".DS_Store"), backingNames(vault.getPath("/target")));
		}

		@Test
		@DisplayName("moving a directory below itself yields EINVAL and keeps everything in it reachable")
		public void testMoveDirectoryBelowItself() {
			long outer = create(ROOT, "outer", NodeType.DIRECTORY).nodeId();
			long inner = create(outer, "inner", NodeType.DIRECTORY).nodeId();
			long innermost = create(inner, "innermost", NodeType.DIRECTORY).nodeId();
			long file = create(outer, "file.txt", NodeType.FILE).nodeId();
			write(file, 0, "content");
			ok(new CloseRequest(file, 0), CloseResponse.class);

			assertStatus(Errno.EINVAL, new RenameRequest(outer, ROOT, "outer", innermost, "moved"));

			Assertions.assertEquals(List.of(".", "..", "outer"), names(readdir(ROOT, 0, 0, false)));
			Assertions.assertTrue(Files.isDirectory(vault.getPath("/outer/inner/innermost")));
			Assertions.assertEquals("content", read(lookup(outer, "file.txt").nodeId(), 0, 100));
		}

		@Test
		@DisplayName("a file that is still open is renamed and moved, and keeps receiving writes")
		public void testRenameOpenFile() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			long file = createFile("file.txt", "one");

			ok(new RenameRequest(file, ROOT, "file.txt", ROOT, "renamed.txt"), RenameResponse.class);
			write(file, 3, " two");
			ok(new RenameRequest(file, ROOT, "renamed.txt", directory, "moved.txt"), RenameResponse.class);
			write(file, 7, " three");
			ok(new CloseRequest(file, 0), CloseResponse.class);

			Assertions.assertEquals("one two three", Files.readString(vault.getPath("/dir/moved.txt")));
			Assertions.assertEquals(List.of(".", "..", "dir"), names(readdir(ROOT, 0, 0, false)));
		}

		@Test
		@DisplayName("moves a directory and a link that replace nothing")
		public void testMoveDirectoryAndLink() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			long target = create(ROOT, "target", NodeType.DIRECTORY).nodeId();
			Files.createSymbolicLink(vault.getPath("/link"), vault.getPath("/elsewhere"));
			long link = lookup(ROOT, "link").nodeId();

			ok(new RenameRequest(directory, ROOT, "dir", target, "moved"), RenameResponse.class);
			ok(new RenameRequest(link, ROOT, "link", target, "moved link"), RenameResponse.class);

			Assertions.assertEquals(List.of(".", "..", "target"), names(readdir(ROOT, 0, 0, false)));
			Assertions.assertEquals(directory, lookup(target, "moved").nodeId());
			Assertions.assertTrue(Files.isSymbolicLink(vault.getPath("/target/moved link")));
		}

		@Test
		@DisplayName("a link replaces a file")
		public void testRenameLinkOverFile() throws IOException {
			long file = createFile("file.txt", "content");
			ok(new CloseRequest(file, 0), CloseResponse.class);
			Files.createSymbolicLink(vault.getPath("/link"), vault.getPath("/elsewhere"));
			long link = lookup(ROOT, "link").nodeId();

			ok(new RenameRequest(link, ROOT, "link", ROOT, "file.txt"), RenameResponse.class);

			Assertions.assertTrue(Files.isSymbolicLink(vault.getPath("/file.txt")));
			Assertions.assertEquals(List.of(".", "..", "file.txt"), names(readdir(ROOT, 0, 0, false)));
		}

		@Test
		@DisplayName("a removed file that is still open stays readable and writable until it is closed")
		public void testRemovedButOpen() {
			long file = createFile("file.txt", "0123456789");

			ok(new RemoveRequest(file, ROOT, "file.txt"), RemoveResponse.class);

			Assertions.assertEquals(List.of(".", ".."), names(readdir(ROOT, 0, 0, false)));
			Assertions.assertEquals("0123456789", read(file, 0, 100));
			Assertions.assertEquals(16, write(file, 10, "abcdef").attributes().size());
			Assertions.assertEquals(4, ok(new SetattrRequest(file, Messages.ATTRIBUTE_SIZE, 4, 0, EPOCH, EPOCH), SetattrResponse.class).attributes().size());
			Assertions.assertEquals("0123", read(file, 0, 100));
			Assertions.assertEquals(4, getattr(file).size());
			ok(new CloseRequest(file, 0), CloseResponse.class);
			ok(new ForgetRequest(file, 1), ForgetResponse.class);
			assertStatus(Errno.ENOENT, new LookupRequest(ROOT, "file.txt"));
		}

		@Test
		@DisplayName("a file re-created under the name of a removed file that is still open is a file of its own")
		public void testRecreateWhileRemovedFileIsOpen() {
			long removed = createFile("file.txt", "removed");
			ok(new RemoveRequest(removed, ROOT, "file.txt"), RemoveResponse.class);

			long recreated = createFile("file.txt", "recreated");
			write(removed, 7, " and still open");

			Assertions.assertNotEquals(removed, recreated);
			Assertions.assertEquals("removed and still open", read(removed, 0, 100));
			Assertions.assertEquals("recreated", read(recreated, 0, 100));
			ok(new CloseRequest(removed, 0), CloseResponse.class);
			Assertions.assertEquals("recreated", read(lookup(ROOT, "file.txt").nodeId(), 0, 100));
		}

		@Test
		@DisplayName("sets times together with a mode that takes the owner's read permission away")
		public void testTimesWithModeRevokingRead() throws IOException {
			long file = createFile("file.txt", "content");
			ok(new CloseRequest(file, 0), CloseResponse.class);
			int valid = Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_MODIFIED;

			SetattrResponse response = ok(new SetattrRequest(file, valid, 0, 0200, EPOCH, new Timestamp(1500000000, 0)), SetattrResponse.class);

			Assertions.assertEquals(valid, response.applied());
			Assertions.assertEquals(0200, response.attributes().mode());
			Assertions.assertEquals("-w-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(vault.getPath("/file.txt"))));
			Assertions.assertEquals(Instant.ofEpochSecond(1500000000), Files.getLastModifiedTime(vault.getPath("/file.txt")).toInstant());
		}

		@Test
		@DisplayName("a time set together with a mode that takes the owner's read permission away outlasts the close of a written file, given the sync macOS sends first")
		public void testTimesWithModeRevokingReadOnOpenFile() throws IOException {
			long file = createFile("file.txt", "content");
			// without it, cryptofs writes the file out when it is closed and cannot set the time again on a file its owner may not read
			ok(new SyncRequest(), SyncResponse.class);

			SetattrResponse response = ok(new SetattrRequest(file, Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_MODIFIED, 0, 0200, EPOCH, new Timestamp(1500000000, 0)), SetattrResponse.class);
			ok(new CloseRequest(file, 0), CloseResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_MODIFIED, response.applied());
			Assertions.assertEquals("-w-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(vault.getPath("/file.txt"))));
			Assertions.assertEquals(Instant.ofEpochSecond(1500000000), Files.getLastModifiedTime(vault.getPath("/file.txt")).toInstant());
		}

		@Test
		@DisplayName("a mode set on a file that was swapped for a link leaves the link's target untouched")
		public void testModeOfFileSwappedForLink() throws IOException {
			long target = createFile("target.txt", "secret");
			ok(new CloseRequest(target, 0), CloseResponse.class);
			long file = createFile("file.txt", "content");
			ok(new CloseRequest(file, 0), CloseResponse.class);
			Files.setPosixFilePermissions(vault.getPath("/target.txt"), PosixFilePermissions.fromString("rw-------"));
			Files.delete(vault.getPath("/file.txt"));
			Files.createSymbolicLink(vault.getPath("/file.txt"), vault.getPath("/target.txt"));

			SetattrResponse response = ok(new SetattrRequest(file, Messages.ATTRIBUTE_MODE, 0, 0666, EPOCH, EPOCH), SetattrResponse.class);

			Assertions.assertEquals(0, response.applied());
			Assertions.assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(vault.getPath("/target.txt"))));
		}

		@Test
		@DisplayName("creating a file under the name of a link yields ENOTSUP and leaves the link's target absent")
		public void testCreateAtLink() throws IOException {
			Files.createSymbolicLink(vault.getPath("/link"), vault.getPath("/elsewhere"));

			assertStatus(Errno.ENOTSUP, new CreateRequest(ROOT, "link", NodeType.FILE, 0644));

			Assertions.assertFalse(Files.exists(vault.getPath("/elsewhere")));
		}

		@Test
		@DisplayName("a link is created with the given target and read back, and its missing target stays absent")
		public void testSymlink() throws IOException {
			SymlinkResponse response = ok(new SymlinkRequest(ROOT, "link", "dir/target.txt"), SymlinkResponse.class);

			Assertions.assertEquals(NodeType.SYMLINK, response.attributes().type());
			Assertions.assertEquals("dir/target.txt", Files.readSymbolicLink(vault.getPath("/link")).toString());
			Assertions.assertEquals("dir/target.txt", ok(new ReadlinkRequest(response.attributes().nodeId()), ReadlinkResponse.class).target());
			Assertions.assertFalse(Files.exists(vault.getPath("/dir")));
		}

		@Test
		@DisplayName("a link created on the vault is read with the target the vault returns")
		public void testReadlink() throws IOException {
			Files.createSymbolicLink(vault.getPath("/link"), vault.getPath("/elsewhere/" + DECOMPOSED));
			long link = lookup(ROOT, "link").nodeId();

			ReadlinkResponse response = ok(new ReadlinkRequest(link), ReadlinkResponse.class);

			Assertions.assertEquals(NodeType.SYMLINK, response.attributes().type());
			Assertions.assertEquals(Files.readSymbolicLink(vault.getPath("/link")).toString(), response.target());
		}

		@Test
		@DisplayName("a link is opened without a channel")
		public void testOpenLink() throws IOException {
			Files.createSymbolicLink(vault.getPath("/link"), vault.getPath("/elsewhere"));
			long link = lookup(ROOT, "link").nodeId();

			ok(new OpenRequest(link, READ | WRITE), OpenResponse.class);

			Assertions.assertEquals(List.of(), ops.openedChannels);
		}

		@Test
		@DisplayName("reading a link whose target is longer than 1023 bytes yields ENAMETOOLONG")
		public void testReadlinkOfLongTarget() throws IOException {
			Files.createSymbolicLink(vault.getPath("/link"), vault.getPath("a".repeat(2000)));
			long link = lookup(ROOT, "link").nodeId();

			assertStatus(Errno.ENAMETOOLONG, new ReadlinkRequest(link));
		}

		@Test
		@DisplayName("a target that the vault's normalization lengthens beyond 1023 bytes yields ENAMETOOLONG and creates nothing")
		public void testSymlinkWithTargetLengthenedByNormalization() {
			// 512 bytes as requested. NFC, the form the vault stores, replaces each character with two of two bytes each.
			String target = "\u0344".repeat(256);

			assertStatus(Errno.ENAMETOOLONG, new SymlinkRequest(ROOT, "link", target));

			Assertions.assertEquals(List.of(".", ".."), names(readdir(ROOT, 0, 0, false)));
		}

		@Test
		@DisplayName("a link created through a decomposed name is found under the composed name, which the reply carries")
		public void testSymlinkDecomposedName() {
			SymlinkResponse created = ok(new SymlinkRequest(ROOT, DECOMPOSED, "target"), SymlinkResponse.class);

			Assertions.assertEquals(COMPOSED, created.name());
			Assertions.assertEquals(created.attributes().nodeId(), lookup(ROOT, COMPOSED).nodeId());
		}

		@Test
		@DisplayName("an entry created through a decomposed name is found under both spellings as one node")
		public void testDecomposedName() {
			long file = createFile(DECOMPOSED, "content");

			Assertions.assertEquals(file, lookup(ROOT, COMPOSED).nodeId());
			Assertions.assertEquals(file, lookup(ROOT, DECOMPOSED).nodeId());
			Assertions.assertEquals(COMPOSED, ok(new LookupRequest(ROOT, DECOMPOSED), LookupResponse.class).name());
		}
	}

	@Nested
	@DisplayName("open, read and write")
	public class OpenReadWrite {

		private long file;

		@BeforeEach
		public void setup() throws IOException {
			backing("file.txt", "0123456789");
			file = lookup(ROOT, "file.txt").nodeId();
		}

		@Test
		@DisplayName("reads without a preceding open")
		public void testReadLazily() {
			Assertions.assertEquals("2345", read(file, 2, 4));
		}

		@Test
		@DisplayName("a read past the end returns what is there")
		public void testReadPastEnd() {
			Assertions.assertEquals("89", read(file, 8, 100));
			Assertions.assertEquals("", read(file, 10, 100));
			Assertions.assertEquals("", read(file, 5000, 100));
		}

		@Test
		@DisplayName("a read replies with the file's attributes")
		public void testReadAttributes() {
			ReadResponse response = ok(new ReadRequest(file, 0, 4), ReadResponse.class);

			Assertions.assertEquals(file, response.attributes().nodeId());
			Assertions.assertEquals(10, response.attributes().size());
		}

		@Test
		@DisplayName("rejects a read longer than a payload can hold")
		public void testReadTooLong() {
			assertStatus(Errno.EINVAL, new ReadRequest(file, 0, FrameCodec.MAX_PAYLOAD_LENGTH + 1));
		}

		@Test
		@DisplayName("a read as long as a payload can hold fits into a frame")
		public void testLongestRead() throws IOException {
			byte[] content = new byte[FrameCodec.MAX_PAYLOAD_LENGTH + 10];
			Arrays.fill(content, (byte) 'x');
			Files.write(root.resolve("file.txt"), content);

			ReadResponse response = ok(new ReadRequest(file, 0, FrameCodec.MAX_PAYLOAD_LENGTH), ReadResponse.class);

			Assertions.assertEquals(FrameCodec.MAX_PAYLOAD_LENGTH, response.data().remaining());
			Frame decoded = FrameCodecTest.read(FrameCodecTest.write(response.toFrame(Opcode.READ, 1)));
			Assertions.assertEquals(ByteBuffer.wrap(content, 0, FrameCodec.MAX_PAYLOAD_LENGTH), decoded.payload());
		}

		@Test
		@DisplayName("reads and writes in full through a channel that transfers one byte per call")
		public void testShortReadsAndWrites() throws IOException {
			ops.channelWrapper = FileSystemOperationsTest::oneBytePerCall;
			ByteBuffer data = StandardCharsets.UTF_8.encode("xxabcdefxx").position(2).limit(8);

			WriteResponse written = ok(new WriteRequest(file, 8, data), WriteResponse.class);

			Assertions.assertEquals(6, written.written());
			Assertions.assertEquals("01234567abcdef", Files.readString(root.resolve("file.txt")));
			Assertions.assertEquals("234567abcdef", read(file, 2, 100));
			Assertions.assertEquals("4567", read(file, 4, 4));
		}

		@Test
		@DisplayName("writes without a preceding open and replies with the grown size")
		public void testWriteLazily() throws IOException {
			WriteResponse response = write(file, 8, "abcdef");

			Assertions.assertEquals(6, response.written());
			Assertions.assertEquals(14, response.attributes().size());
			Assertions.assertTrue(response.freeSpace().usableBytes() > 0);
			Assertions.assertEquals("01234567abcdef", Files.readString(root.resolve("file.txt")));
		}

		@Test
		@DisplayName("writes the part of a buffer between its position and limit")
		public void testWriteBufferSlice() throws IOException {
			ByteBuffer data = StandardCharsets.UTF_8.encode("xxABCxx").position(2).limit(5);

			WriteResponse response = ok(new WriteRequest(file, 0, data), WriteResponse.class);

			Assertions.assertEquals(3, response.written());
			Assertions.assertEquals("ABC3456789", Files.readString(root.resolve("file.txt")));
		}

		@Test
		@DisplayName("widening the open modes swaps in a new channel and closes the old one")
		public void testWidenModes() {
			ok(new OpenRequest(file, READ), OpenResponse.class);
			write(file, 0, "ab");

			Assertions.assertEquals(2, ops.openedChannels.size());
			Assertions.assertFalse(ops.openedChannels.get(0).isOpen());
			Assertions.assertTrue(ops.openedChannels.get(1).isOpen());
			Assertions.assertEquals("ab23", read(file, 0, 4));
			Assertions.assertEquals(2, ops.openedChannels.size());
		}

		@Test
		@DisplayName("opening with modes already granted keeps the channel")
		public void testReopenSameModes() {
			ok(new OpenRequest(file, READ | WRITE), OpenResponse.class);
			ok(new OpenRequest(file, READ), OpenResponse.class);
			ok(new OpenRequest(file, WRITE), OpenResponse.class);

			Assertions.assertEquals(1, ops.openedChannels.size());
		}

		@Test
		@DisplayName("a close that keeps no mode closes the channel, one that keeps a mode does not")
		public void testClose() {
			ok(new OpenRequest(file, READ | WRITE), OpenResponse.class);

			ok(new CloseRequest(file, READ), CloseResponse.class);
			Assertions.assertTrue(ops.openedChannels.getFirst().isOpen());

			CloseResponse response = ok(new CloseRequest(file, 0), CloseResponse.class);
			Assertions.assertFalse(ops.openedChannels.getFirst().isOpen());
			Assertions.assertTrue(response.freeSpace().usableBytes() > 0);
		}

		@Test
		@DisplayName("a forget closes the channel")
		public void testForgetClosesChannel() {
			ok(new OpenRequest(file, READ), OpenResponse.class);

			ok(new ForgetRequest(file, 1), ForgetResponse.class);

			Assertions.assertFalse(ops.openedChannels.getFirst().isOpen());
		}

		@Test
		@DisplayName("directories get no channel")
		public void testOpenDirectory() {
			ok(new OpenRequest(ROOT, READ), OpenResponse.class);
			ok(new CloseRequest(ROOT, 0), CloseResponse.class);

			Assertions.assertEquals(List.of(), ops.openedChannels);
			assertStatus(Errno.EISDIR, new ReadRequest(ROOT, 0, 10));
			assertStatus(Errno.EISDIR, new WriteRequest(ROOT, 0, ByteBuffer.allocate(1)));
		}

		@Test
		@DisplayName("sync forces every open channel and reports the first failure")
		public void testSync() throws IOException {
			backing("other.txt", "");
			long other = lookup(ROOT, "other.txt").nodeId();
			FileChannel failing = Mockito.mock(FileChannel.class);
			FileChannel working = Mockito.mock(FileChannel.class);
			Mockito.doThrow(new IOException("disk on fire")).when(failing).force(false);
			ops.channelWrapper = _ -> failing;
			ok(new OpenRequest(file, WRITE), OpenResponse.class);
			ops.channelWrapper = _ -> working;
			ok(new OpenRequest(other, WRITE), OpenResponse.class);

			assertStatus(Errno.EIO, new SyncRequest());
			Mockito.verify(failing).force(false);
			Mockito.verify(working).force(false);

			Mockito.doNothing().when(failing).force(false);
			Assertions.assertTrue(ok(new SyncRequest(), SyncResponse.class).freeSpace().usableBytes() > 0);
		}

		@Test
		@DisplayName("sync leaves out the channel of a file that was closed or forgotten")
		public void testSyncAfterCloseAndForget() throws IOException {
			backing("other.txt", "");
			long other = lookup(ROOT, "other.txt").nodeId();
			FileChannel closed = Mockito.mock(FileChannel.class);
			FileChannel forgotten = Mockito.mock(FileChannel.class);
			ops.channelWrapper = _ -> closed;
			ok(new OpenRequest(file, WRITE), OpenResponse.class);
			ops.channelWrapper = _ -> forgotten;
			ok(new OpenRequest(other, WRITE), OpenResponse.class);

			ok(new CloseRequest(file, 0), CloseResponse.class);
			ok(new ForgetRequest(other, 1), ForgetResponse.class);
			ok(new SyncRequest(), SyncResponse.class);

			Mockito.verify(closed, Mockito.never()).force(false);
			Mockito.verify(forgotten, Mockito.never()).force(false);
		}

		@Test
		@DisplayName("a channel that fails to close is gone all the same, and so is its node when forgotten")
		public void testSyncAfterFailedCloseAndForget() throws IOException {
			backing("other.txt", "");
			long other = lookup(ROOT, "other.txt").nodeId();
			FileChannel failing = Mockito.mock(FileChannel.class);
			Mockito.doThrow(new IOException("disk on fire")).when(failing).close();
			Mockito.when(failing.read(Mockito.any(ByteBuffer.class), Mockito.anyLong())).thenThrow(new ClosedChannelException());
			ops.channelWrapper = _ -> failing;
			ok(new OpenRequest(file, READ), OpenResponse.class);
			ok(new OpenRequest(other, WRITE), OpenResponse.class);
			ops.channelWrapper = UnaryOperator.identity();

			assertStatus(Errno.EIO, new CloseRequest(file, 0));
			assertStatus(Errno.EIO, new ForgetRequest(other, 1));

			ok(new SyncRequest(), SyncResponse.class);
			Mockito.verify(failing, Mockito.never()).force(false);
			Assertions.assertEquals("0123", read(file, 0, 4));
			assertStatus(Errno.ESTALE, new GetattrRequest(other));
		}
	}

	@Nested
	@DisplayName("setattr")
	public class Setattr {

		private static final Timestamp ACCESSED = new Timestamp(1600000000, 0);
		private static final Timestamp MODIFIED = new Timestamp(1500000000, 0);
		private static final int TIMES = Messages.ATTRIBUTE_ACCESSED | Messages.ATTRIBUTE_MODIFIED;

		private final HookedOperations.Hook refusing = path -> {
			throw new AccessDeniedException(path.toString());
		};
		private final HookedOperations.Hook refusingUnchecked = path -> {
			throw new UncheckedIOException(new AccessDeniedException(path.toString()));
		};
		private long file;

		@BeforeEach
		public void setup() throws IOException {
			backing("file.txt", "0123456789");
			file = lookup(ROOT, "file.txt").nodeId();
		}

		private SetattrResponse setSize(long size) {
			return ok(new SetattrRequest(file, Messages.ATTRIBUTE_SIZE, size, 0, EPOCH, EPOCH), SetattrResponse.class);
		}

		private SetattrRequest modeAndTimes(long node, int mode) {
			return new SetattrRequest(node, Messages.ATTRIBUTE_MODE | TIMES, 0, mode, ACCESSED, MODIFIED);
		}

		private Instant backingAccessTime(Path path) throws IOException {
			return ((FileTime) Files.getAttribute(path, "lastAccessTime")).toInstant();
		}

		@Test
		@DisplayName("truncates a file")
		public void testTruncate() throws IOException {
			SetattrResponse response = setSize(4);

			Assertions.assertEquals(Messages.ATTRIBUTE_SIZE, response.applied());
			Assertions.assertEquals(4, response.attributes().size());
			Assertions.assertTrue(response.freeSpace().usableBytes() > 0);
			Assertions.assertEquals("0123", Files.readString(root.resolve("file.txt")));
			Assertions.assertEquals(List.of(), ops.openedChannels.stream().filter(FileChannel::isOpen).toList());
		}

		@Test
		@DisplayName("extends a file with zeros")
		public void testExtend() throws IOException {
			SetattrResponse response = setSize(10000);

			Assertions.assertEquals(10000, response.attributes().size());
			byte[] content = Files.readAllBytes(root.resolve("file.txt"));
			Assertions.assertEquals(10000, content.length);
			Assertions.assertEquals("0123456789", new String(content, 0, 10, StandardCharsets.UTF_8));
			for (int i = 10; i < content.length; i++) {
				Assertions.assertEquals(0, content[i], "byte " + i);
			}
		}

		@Test
		@DisplayName("truncates through the open channel when that is writable")
		public void testTruncateThroughWritableChannel() {
			ok(new OpenRequest(file, READ | WRITE), OpenResponse.class);

			setSize(4);

			Assertions.assertEquals(1, ops.openedChannels.size());
			Assertions.assertEquals("0123", read(file, 0, 100));
		}

		@Test
		@DisplayName("truncates a file whose open channel is read-only")
		public void testTruncateWithReadOnlyChannel() {
			ok(new OpenRequest(file, READ), OpenResponse.class);

			setSize(4);

			Assertions.assertEquals("0123", read(file, 0, 100));
			Assertions.assertTrue(ops.openedChannels.get(0).isOpen());
			Assertions.assertFalse(ops.openedChannels.get(1).isOpen());
		}

		@Test
		@DisplayName("changes the permissions of an entry that lacks read permission")
		public void testModeOfUnreadableEntries() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			for (long node : List.of(file, directory)) {
				ok(new SetattrRequest(node, Messages.ATTRIBUTE_MODE, 0, 0311, EPOCH, EPOCH), SetattrResponse.class);

				SetattrResponse response = ok(new SetattrRequest(node, Messages.ATTRIBUTE_MODE, 0, 0700, EPOCH, EPOCH), SetattrResponse.class);

				Assertions.assertEquals(Messages.ATTRIBUTE_MODE, response.applied());
				Assertions.assertEquals(0700, response.attributes().mode());
			}
			Assertions.assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("dir"))));
			Assertions.assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("file.txt"))));
		}

		@Test
		@DisplayName("sets times together with a mode that takes the owner's read permission away")
		public void testTimesWithModeRevokingRead() throws IOException {
			create(ROOT, "dir", NodeType.DIRECTORY);
			for (String name : List.of("file.txt", "dir")) {
				long node = lookup(ROOT, name).nodeId();
				try {
					SetattrResponse response = ok(modeAndTimes(node, 0244), SetattrResponse.class);

					Assertions.assertEquals(Messages.ATTRIBUTE_MODE | TIMES, response.applied());
					Assertions.assertEquals(0244, response.attributes().mode());
					Assertions.assertEquals(MODIFIED, response.attributes().modified());
					Assertions.assertEquals(ACCESSED, response.attributes().accessed());
					Assertions.assertEquals("-w-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(name))));
					Assertions.assertEquals(Instant.ofEpochSecond(MODIFIED.seconds()), Files.getLastModifiedTime(root.resolve(name)).toInstant());
					Assertions.assertEquals(Instant.ofEpochSecond(ACCESSED.seconds()), backingAccessTime(root.resolve(name)));
				} finally {
					ok(new SetattrRequest(node, Messages.ATTRIBUTE_MODE, 0, 0700, EPOCH, EPOCH), SetattrResponse.class);
				}
			}
		}

		@Test
		@DisplayName("sets times together with a mode that grants the owner read permission")
		public void testTimesWithModeGrantingRead() throws IOException {
			Path path = root.resolve("file.txt");
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));

			SetattrResponse response = ok(modeAndTimes(file, 0600), SetattrResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_MODE | TIMES, response.applied());
			Assertions.assertEquals(0600, response.attributes().mode());
			Assertions.assertEquals(MODIFIED, response.attributes().modified());
			Assertions.assertEquals(ACCESSED, response.attributes().accessed());
			Assertions.assertEquals(10, response.attributes().size());
			Assertions.assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
			Assertions.assertEquals(Instant.ofEpochSecond(MODIFIED.seconds()), Files.getLastModifiedTime(path).toInstant());
			Assertions.assertEquals(Instant.ofEpochSecond(ACCESSED.seconds()), backingAccessTime(path));
		}

		@Test
		@DisplayName("times together with a mode that leaves an unreadable file unreadable yield EACCES and leave the mode unchanged")
		public void testTimesWithModeOfUnreadableFile() throws IOException {
			Path path = root.resolve("file.txt");
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"));
			Assumptions.assumeFalse(Files.isReadable(path), "permissions do not apply to this user");

			assertStatus(Errno.EACCES, modeAndTimes(file, 0200));

			Assertions.assertEquals("---------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
		}

		@Test
		@DisplayName("a mode whose entry cannot be read anew yields the status of that failure, and neither the mode nor a size is applied")
		public void testModeOfUnreadableEntry() throws IOException {
			Path path = root.resolve("file.txt");
			String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
			ops.beforeReadingAttributes = _ -> {
				throw new IOException("backend gone");
			};

			assertStatus(Errno.EIO, new SetattrRequest(file, Messages.ATTRIBUTE_SIZE | Messages.ATTRIBUTE_MODE, 4, 0750, EPOCH, EPOCH));

			Assertions.assertEquals("0123456789", Files.readString(path));
			Assertions.assertEquals(permissions, PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
		}

		@Test
		@DisplayName("truncates before it sets times that go ahead of the mode")
		public void testSizeBeforeTimes() throws IOException {
			int valid = Messages.ATTRIBUTE_SIZE | Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_MODIFIED;

			SetattrResponse response = ok(new SetattrRequest(file, valid, 4, 0200, EPOCH, MODIFIED), SetattrResponse.class);

			Assertions.assertEquals(valid, response.applied());
			Assertions.assertEquals(4, response.attributes().size());
			Assertions.assertEquals(Instant.ofEpochSecond(MODIFIED.seconds()), Files.getLastModifiedTime(root.resolve("file.txt")).toInstant());
		}

		@Test
		@DisplayName("times refused after the mode was applied are left out of a reply that still succeeds")
		public void testTimesRefusedAfterMode() throws IOException {
			Attributes before = getattr(file);
			ops.beforeSettingTimes = refusing;

			SetattrResponse response = ok(modeAndTimes(file, 0600), SetattrResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_MODE, response.applied());
			Assertions.assertEquals(0600, response.attributes().mode());
			Assertions.assertEquals(before.modified(), response.attributes().modified());
			Assertions.assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("file.txt"))));
		}

		@Test
		@DisplayName("a mode refused after the times were applied is left out of a reply that still succeeds")
		public void testModeRefusedAfterTimes() throws IOException {
			Path path = root.resolve("file.txt");
			ops.beforeSettingPermissions = refusing;

			SetattrResponse response = ok(modeAndTimes(file, 0200), SetattrResponse.class);

			Assertions.assertEquals(TIMES, response.applied());
			Assertions.assertEquals(MODIFIED, response.attributes().modified());
			Assertions.assertEquals(Instant.ofEpochSecond(MODIFIED.seconds()), Files.getLastModifiedTime(path).toInstant());
		}

		@Test
		@DisplayName("after a refused mode that follows a truncation, the times are not attempted")
		public void testModeRefusedAfterSize() throws IOException {
			List<Path> timesSet = new ArrayList<>();
			ops.beforeSettingPermissions = refusingUnchecked;
			ops.beforeSettingTimes = timesSet::add;
			int valid = Messages.ATTRIBUTE_SIZE | Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_MODIFIED;

			SetattrResponse response = ok(new SetattrRequest(file, valid, 4, 0600, EPOCH, MODIFIED), SetattrResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_SIZE, response.applied());
			Assertions.assertEquals(4, response.attributes().size());
			Assertions.assertEquals("0123", Files.readString(root.resolve("file.txt")));
			Assertions.assertEquals(List.of(), timesSet);
		}

		@Test
		@DisplayName("after refused times that follow a truncation, the mode is not attempted")
		public void testTimesRefusedAfterSize() throws IOException {
			List<Path> permissionsSet = new ArrayList<>();
			ops.beforeSettingTimes = refusing;
			ops.beforeSettingPermissions = permissionsSet::add;
			int valid = Messages.ATTRIBUTE_SIZE | Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_MODIFIED;

			SetattrResponse response = ok(new SetattrRequest(file, valid, 4, 0200, EPOCH, MODIFIED), SetattrResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_SIZE, response.applied());
			Assertions.assertEquals("0123", Files.readString(root.resolve("file.txt")));
			Assertions.assertEquals(List.of(), permissionsSet);
		}

		@Test
		@DisplayName("a mode refused before anything was applied yields its status and leaves the times unchanged")
		public void testModeRefusedFirst() throws IOException {
			Path path = root.resolve("file.txt");
			String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
			FileTime modified = Files.getLastModifiedTime(path);
			ops.beforeSettingPermissions = refusing;

			assertStatus(Errno.EACCES, modeAndTimes(file, 0600));

			Assertions.assertEquals(permissions, PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
			Assertions.assertEquals(modified, Files.getLastModifiedTime(path));
		}

		@Test
		@DisplayName("sets only the modification time when only that is valid")
		public void testModifiedOnly() throws IOException {
			Path path = root.resolve("file.txt");
			Files.setAttribute(path, "lastAccessTime", FileTime.from(Instant.ofEpochSecond(1400000000)));

			SetattrResponse response = ok(new SetattrRequest(file, Messages.ATTRIBUTE_MODIFIED, 0, 0, EPOCH, new Timestamp(1500000000, 0)), SetattrResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_MODIFIED, response.applied());
			Assertions.assertEquals(new Timestamp(1400000000, 0), response.attributes().accessed());
			Assertions.assertEquals(new Timestamp(1500000000, 0), response.attributes().modified());
		}

		@Test
		@DisplayName("rejects a size or time it cannot represent with EINVAL and applies nothing")
		public void testUnrepresentableSizeAndTimes() throws IOException {
			Path path = root.resolve("file.txt");
			String permissions = PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
			int sizeAndMode = Messages.ATTRIBUTE_SIZE | Messages.ATTRIBUTE_MODE;
			List<Timestamp> unrepresentable = List.of( //
					new Timestamp(Long.MAX_VALUE, 0), //
					new Timestamp(Long.MIN_VALUE, 0), //
					new Timestamp(0, 1_000_000_000), //
					// nanoseconds beyond what an int holds, as they are decoded
					new Timestamp(0, -1));

			assertStatus(Errno.EINVAL, new SetattrRequest(file, sizeAndMode, -1, 0700, EPOCH, EPOCH));
			for (Timestamp time : unrepresentable) {
				assertStatus(Errno.EINVAL, new SetattrRequest(file, sizeAndMode | Messages.ATTRIBUTE_ACCESSED, 4, 0700, time, EPOCH));
				assertStatus(Errno.EINVAL, new SetattrRequest(file, sizeAndMode | Messages.ATTRIBUTE_MODIFIED, 4, 0700, EPOCH, time));
			}

			Assertions.assertEquals("0123456789", Files.readString(path));
			Assertions.assertEquals(permissions, PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
		}

		@Test
		@DisplayName("does not apply a size to a directory")
		public void testSizeOfDirectory() {
			SetattrResponse response = ok(new SetattrRequest(ROOT, Messages.ATTRIBUTE_SIZE, 0, 0, EPOCH, EPOCH), SetattrResponse.class);

			Assertions.assertEquals(0, response.applied());
		}
	}

	@Nested
	@DisplayName("readdir")
	public class Readdir {

		private static final int ENTRIES = 250;
		private static final String LONG_NAME_PREFIX = "x".repeat(200);

		/**
		 * Creates enough entries with long names to need more than one page.
		 */
		private Set<String> createManyEntries() throws IOException {
			Set<String> names = new HashSet<>();
			for (int i = 0; i < ENTRIES; i++) {
				String name = LONG_NAME_PREFIX + i;
				backing(name, "");
				names.add(name);
			}
			return names;
		}

		@Test
		@DisplayName("a listing of names starts with . and ..")
		public void testNamesStartWithDotEntries() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			long file = create(directory, "file.txt", NodeType.FILE).nodeId();

			ReaddirResponse page = readdir(directory, 0, 0, false);

			Assertions.assertFalse(page.more());
			Assertions.assertEquals(List.of( //
					new DirectoryEntry(".", NodeType.DIRECTORY, directory, 1, null), //
					new DirectoryEntry("..", NodeType.DIRECTORY, ROOT, 2, null), //
					new DirectoryEntry("file.txt", NodeType.FILE, file, 3, null)), page.entries());
		}

		@Test
		@DisplayName("the root's .. entry is the root itself")
		public void testRootDotEntries() {
			Assertions.assertEquals(List.of( //
					new DirectoryEntry(".", NodeType.DIRECTORY, ROOT, 1, null), //
					new DirectoryEntry("..", NodeType.DIRECTORY, ROOT, 2, null)), readdir(ROOT, 0, 0, false).entries());
		}

		@Test
		@DisplayName("a listing with attributes omits . and ..")
		public void testAttributesOmitDotEntries() throws IOException {
			backing("file.txt", "12345");
			Files.createDirectory(root.resolve("dir"));

			ReaddirResponse page = readdir(ROOT, 0, 0, true);

			Assertions.assertEquals(Set.of("file.txt", "dir"), Set.copyOf(names(page)));
			for (DirectoryEntry entry : page.entries()) {
				Assertions.assertEquals(entry.nodeId(), entry.attributes().nodeId());
				Assertions.assertEquals(entry.type(), entry.attributes().type());
				Assertions.assertEquals(ROOT, entry.attributes().parentId());
				Assertions.assertEquals(entry.nodeId(), lookup(ROOT, entry.name()).nodeId());
			}
			DirectoryEntry file = page.entries().stream().filter(entry -> entry.name().equals("file.txt")).findFirst().orElseThrow();
			Assertions.assertEquals(5, file.attributes().size());
			Assertions.assertEquals(NodeType.FILE, file.type());
		}

		@ParameterizedTest(name = "wantAttributes = {0}")
		@DisplayName("paging by cookie returns every entry once, and the cookie at the end returns an empty page")
		@ValueSource(booleans = {false, true})
		public void testPaging(boolean wantAttributes) throws IOException {
			Set<String> expected = createManyEntries();
			if (!wantAttributes) {
				expected.addAll(List.of(".", ".."));
			}

			List<String> listed = new ArrayList<>();
			int pages = 0;
			ReaddirResponse page = readdir(ROOT, 0, 0, wantAttributes);
			long verifier = page.verifier();
			while (true) {
				pages++;
				listed.addAll(names(page));
				Assertions.assertEquals(verifier, page.verifier());
				if (!page.more()) {
					break;
				}
				page = readdir(ROOT, page.entries().getLast().nextCookie(), verifier, wantAttributes);
			}

			Assertions.assertTrue(pages > 1, "pages");
			Assertions.assertEquals(expected.size(), listed.size());
			Assertions.assertEquals(expected, Set.copyOf(listed));
			Assertions.assertEquals(ENTRIES + 2, page.entries().getLast().nextCookie());
			ReaddirResponse end = readdir(ROOT, ENTRIES + 2, verifier, wantAttributes);
			Assertions.assertEquals(List.of(), end.entries());
			Assertions.assertFalse(end.more());
		}

		@Test
		@DisplayName("a size change between two pages shows up in the later page")
		public void testAttributesAreReadPerPage() throws IOException {
			createManyEntries();
			ReaddirResponse first = readdir(ROOT, 0, 0, true);
			long cookie = first.entries().getLast().nextCookie();
			String later = readdir(ROOT, cookie, first.verifier(), false).entries().getFirst().name();

			backing(later, "grown");
			DirectoryEntry entry = readdir(ROOT, cookie, first.verifier(), true).entries().getFirst();

			Assertions.assertEquals(later, entry.name());
			Assertions.assertEquals(5, entry.attributes().size());
		}

		@Test
		@DisplayName("after a lookup and forget of a listed entry, continuing the listing reports the id a new lookup returns")
		public void testListedIdAfterForget() throws IOException {
			backing("file.txt", "");
			ReaddirResponse page = readdir(ROOT, 0, 0, false);
			long listed = page.entries().get(2).nodeId();
			Assertions.assertEquals(listed, lookup(ROOT, "file.txt").nodeId());
			ok(new ForgetRequest(listed, 1), ForgetResponse.class);

			long relisted = readdir(ROOT, 2, page.verifier(), false).entries().getFirst().nodeId();

			Assertions.assertNotEquals(listed, relisted);
			Assertions.assertEquals(relisted, lookup(ROOT, "file.txt").nodeId());
		}

		@Test
		@DisplayName("cookie 0 starts a new listing whatever verifier is sent, and no verifier is 0")
		public void testCookieZero() throws IOException {
			long first = readdir(ROOT, 0, 0, false).verifier();
			backing("file.txt", "");
			ReaddirResponse second = readdir(ROOT, 0, first, false);
			ReaddirResponse third = readdir(ROOT, 0, 4711, false);

			Assertions.assertNotEquals(0, first);
			Assertions.assertNotEquals(0, second.verifier());
			Assertions.assertNotEquals(first, second.verifier());
			Assertions.assertNotEquals(second.verifier(), third.verifier());
			Assertions.assertEquals(List.of(".", "..", "file.txt"), names(second));
		}

		@Test
		@DisplayName("a stale verifier or a cookie beyond the listing yields the invalid-cookie status")
		public void testInvalidCookie() throws IOException {
			backing("file.txt", "");
			long other = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			long verifier = readdir(ROOT, 0, 0, false).verifier();

			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(ROOT, 1, verifier + 1, false));
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(ROOT, 1, 0, false));
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(ROOT, 5, verifier, false));
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(ROOT, -1, verifier, false));
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(other, 1, verifier, false));
			Assertions.assertEquals(List.of(), readdir(ROOT, 4, verifier, false).entries());
		}

		@Test
		@DisplayName("create, symlink and remove invalidate the listings of their directory, rename those of its two directories")
		public void testInvalidation() {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			long file = create(directory, "file.txt", NodeType.FILE).nodeId();

			long rootVerifier = readdir(ROOT, 0, 0, false).verifier();
			long verifier = readdir(directory, 0, 0, false).verifier();
			create(directory, "created.txt", NodeType.FILE);
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(directory, 1, verifier, false));
			readdir(ROOT, 1, rootVerifier, false);

			verifier = readdir(directory, 0, 0, false).verifier();
			ok(new SymlinkRequest(directory, "link", "file.txt"), SymlinkResponse.class);
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(directory, 1, verifier, false));
			readdir(ROOT, 1, rootVerifier, false);

			verifier = readdir(directory, 0, 0, false).verifier();
			ok(new RemoveRequest(file, directory, "file.txt"), RemoveResponse.class);
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(directory, 1, verifier, false));
			readdir(ROOT, 1, rootVerifier, false);

			long other = create(ROOT, "other", NodeType.DIRECTORY).nodeId();
			rootVerifier = readdir(ROOT, 0, 0, false).verifier();
			verifier = readdir(directory, 0, 0, false).verifier();
			long otherVerifier = readdir(other, 0, 0, false).verifier();
			ok(new RenameRequest(lookup(directory, "created.txt").nodeId(), directory, "created.txt", other, "renamed.txt"), RenameResponse.class);
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(directory, 1, verifier, false));
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(other, 1, otherVerifier, false));
			readdir(ROOT, 1, rootVerifier, false);
		}

		@Test
		@DisplayName("holds at most 16 listings and drops the one that was paged through least recently")
		public void testListingCap() {
			long first = readdir(ROOT, 0, 0, false).verifier();
			long second = readdir(ROOT, 0, 0, false).verifier();
			for (int i = 0; i < 14; i++) {
				readdir(ROOT, 0, 0, false);
			}
			readdir(ROOT, 1, first, false);

			readdir(ROOT, 0, 0, false);

			readdir(ROOT, 1, first, false);
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(ROOT, 1, second, false));
		}

		@Test
		@DisplayName("invalidating a directory's listings releases the nodes that were only listed")
		public void testInvalidationReleasesListedNodes() throws IOException {
			backing("listed.txt", "");
			backing("held.txt", "");
			long held = lookup(ROOT, "held.txt").nodeId();
			long link = ok(new SymlinkRequest(ROOT, "link", "held.txt"), SymlinkResponse.class).attributes().nodeId();
			long listed = readdir(ROOT, 0, 0, false).entries().stream().filter(entry -> entry.name().equals("listed.txt")).findFirst().orElseThrow().nodeId();
			Assertions.assertEquals(listed, getattr(listed).nodeId());

			create(ROOT, "created.txt", NodeType.FILE);

			assertStatus(Errno.ESTALE, new GetattrRequest(listed));
			Assertions.assertEquals(held, getattr(held).nodeId());
			Assertions.assertEquals(link, getattr(link).nodeId());
		}

		@Test
		@DisplayName("an open, read or write of a file that was only listed is refused as stale and opens no channel")
		public void testListedNodeGetsNoChannel() throws IOException {
			backing("listed.txt", "content");
			long listed = readdir(ROOT, 0, 0, false).entries().get(2).nodeId();

			assertStatus(Errno.ESTALE, new OpenRequest(listed, Messages.MODE_READ));
			assertStatus(Errno.ESTALE, new ReadRequest(listed, 0, 7));
			assertStatus(Errno.ESTALE, new WriteRequest(listed, 0, StandardCharsets.UTF_8.encode("data")));
			Assertions.assertEquals(List.of(), ops.openedChannels);
		}

		@Test
		@DisplayName("an entry that vanished since the listing was taken is left out of a page with attributes")
		public void testVanishedEntry() throws IOException {
			backing("stays.txt", "");
			Path vanishing = backing("vanishes.txt", "");
			long verifier = readdir(ROOT, 0, 0, false).verifier();

			Files.delete(vanishing);
			ReaddirResponse page = readdir(ROOT, 2, verifier, true);

			Assertions.assertEquals(List.of("stays.txt"), names(page));
			Assertions.assertFalse(page.more());
		}

		@Test
		@DisplayName("an entry that vanishes while the listing is taken is left out")
		public void testVanishingWhileListing() throws IOException {
			backing("stays.txt", "");
			backing("vanishes.txt", "");
			ops.beforeReadingAttributes = path -> {
				if (path.endsWith("vanishes.txt")) {
					throw new NoSuchFileException(path.toString());
				}
			};

			Assertions.assertEquals(List.of(".", "..", "stays.txt"), names(readdir(ROOT, 0, 0, false)));
		}

		@ParameterizedTest(name = "wantAttributes = {0}")
		@DisplayName("a listing that fits into one page reads the attributes of each entry once")
		@ValueSource(booleans = {false, true})
		public void testAttributesAreReadOnce(boolean wantAttributes) throws IOException {
			backing("file.txt", "");
			Files.createDirectory(root.resolve("dir"));
			List<Path> read = new ArrayList<>();
			ops.beforeReadingAttributes = read::add;

			readdir(ROOT, 0, 0, wantAttributes);

			Assertions.assertEquals(Set.of(root.resolve("file.txt"), root.resolve("dir")), Set.copyOf(read));
			Assertions.assertEquals(2, read.size());
		}

		@Test
		@DisplayName("a listing that started with attributes reports each entry's type on a page without them")
		public void testTypesAfterListingWithAttributes() throws IOException {
			backing("file.txt", "");
			Files.createDirectory(root.resolve("dir"));
			long verifier = readdir(ROOT, 0, 0, true).verifier();

			ReaddirResponse page = readdir(ROOT, 2, verifier, false);

			Assertions.assertEquals(Set.of("file.txt", "dir"), Set.copyOf(names(page)));
			for (DirectoryEntry entry : page.entries()) {
				Assertions.assertEquals(lookup(ROOT, entry.name()).type(), entry.type());
				Assertions.assertNull(entry.attributes());
			}
		}

		@ParameterizedTest(name = "wantAttributes = {0}")
		@DisplayName("every page of names that take three bytes per character fits into a frame")
		@ValueSource(booleans = {false, true})
		public void testFullPagesOfMultibyteNames(boolean wantAttributes) throws IOException {
			Set<String> expected = new HashSet<>();
			for (int i = 0; i < 600; i++) {
				String name = "\u20ac".repeat(80) + i;
				backing(name, "");
				expected.add(name);
			}

			Set<String> listed = new HashSet<>();
			ReaddirResponse page = readdir(ROOT, 0, 0, wantAttributes);
			int pages = 1;
			while (true) {
				Frame decoded = FrameCodecTest.read(FrameCodecTest.write(page.toFrame(Opcode.READDIR, pages)));
				Assertions.assertEquals(page, Messages.decodeResponse(decoded));
				listed.addAll(names(page));
				if (!page.more()) {
					break;
				}
				page = readdir(ROOT, page.entries().getLast().nextCookie(), page.verifier(), wantAttributes);
				pages++;
			}

			Assertions.assertTrue(pages > 2, "pages");
			listed.removeAll(List.of(".", ".."));
			Assertions.assertEquals(expected, listed);
		}

		@Test
		@DisplayName("listing a file yields ENOTDIR")
		public void testReaddirOfFile() throws IOException {
			backing("file.txt", "");

			assertStatus(Errno.ENOTDIR, new ReaddirRequest(lookup(ROOT, "file.txt").nodeId(), 0, 0, false));
		}
	}

	@Nested
	@DisplayName("symbolic links")
	public class SymbolicLinks {

		private Path outside;
		private long link;

		@BeforeEach
		public void setup(@TempDir Path outsideDir) throws IOException {
			outside = outsideDir;
			Files.writeString(outside.resolve("secret.txt"), "secret");
			Files.createSymbolicLink(root.resolve("link"), outside);
			link = lookup(ROOT, "link").nodeId();
		}

		@Test
		@DisplayName("a link is reported as a link")
		public void testReportedAsLink() {
			Assertions.assertEquals(NodeType.SYMLINK, getattr(link).type());
			Assertions.assertEquals(NodeType.SYMLINK, readdir(ROOT, 0, 0, false).entries().get(2).type());
			Assertions.assertEquals(NodeType.SYMLINK, readdir(ROOT, 0, 0, true).entries().getFirst().attributes().type());
		}

		@Test
		@DisplayName("a link cannot be traversed, read or written")
		public void testNotFollowed() {
			assertStatus(Errno.ENOTDIR, new LookupRequest(link, "secret.txt"));
			assertStatus(Errno.ENOTDIR, new ReaddirRequest(link, 0, 0, false));
			assertStatus(Errno.ENOTDIR, new CreateRequest(link, "new.txt", NodeType.FILE, 0644));
			assertStatus(Errno.ENOTDIR, new SymlinkRequest(link, "new", "target"));
			assertStatus(Errno.ENOTDIR, new RemoveRequest(link, link, "link"));
			assertStatus(Errno.ENOTDIR, new RenameRequest(link, link, "link", ROOT, "moved"));
			assertStatus(Errno.ENOTDIR, new RenameRequest(link, ROOT, "link", link, "moved"));
			// the parent is checked before the name
			assertStatus(Errno.ENOTDIR, new CreateRequest(link, "._new", NodeType.FILE, 0644));
			assertStatus(Errno.ENOTDIR, new SymlinkRequest(link, "a/b", "target"));
			assertStatus(Errno.ENOTSUP, new ReadRequest(link, 0, 10));
			assertStatus(Errno.ENOTSUP, new WriteRequest(link, 0, ByteBuffer.allocate(1)));
		}

		@Test
		@DisplayName("a link gets no channel when it is opened, as macOS does to copy the link itself")
		public void testOpen() {
			ok(new OpenRequest(link, READ | WRITE), OpenResponse.class);
			ok(new CloseRequest(link, 0), CloseResponse.class);

			Assertions.assertEquals(List.of(), ops.openedChannels);
		}

		@Test
		@DisplayName("reads a link's target and replies with the link's attributes")
		public void testReadlink() {
			ReadlinkResponse response = ok(new ReadlinkRequest(link), ReadlinkResponse.class);

			Assertions.assertEquals(outside.toString(), response.target());
			Assertions.assertEquals(NodeType.SYMLINK, response.attributes().type());
			Assertions.assertEquals(link, response.attributes().nodeId());
		}

		@Test
		@DisplayName("reading a file as a link yields EINVAL")
		public void testReadlinkOfFile() throws IOException {
			backing("file.txt", "content");

			assertStatus(Errno.EINVAL, new ReadlinkRequest(lookup(ROOT, "file.txt").nodeId()));
		}

		@Test
		@DisplayName("reading a link that was removed yields ESTALE")
		public void testReadlinkOfRemovedLink() {
			ok(new RemoveRequest(link, ROOT, "link"), RemoveResponse.class);

			assertStatus(Errno.ESTALE, new ReadlinkRequest(link));
		}

		@ParameterizedTest(name = "{0}")
		@DisplayName("creates a link to a missing target with the target as given, whether relative, absolute or not ASCII")
		@ValueSource(strings = {"sub/target.txt", "../target.txt", "/absolute/target.txt", "t\u00e4rget.txt"})
		public void testSymlink(String target) throws IOException {
			SymlinkResponse response = ok(new SymlinkRequest(ROOT, "new", target), SymlinkResponse.class);

			Assertions.assertEquals(target, Files.readSymbolicLink(root.resolve("new")).toString());
			Assertions.assertEquals("new", response.name());
			Assertions.assertEquals(NodeType.SYMLINK, response.attributes().type());
			Assertions.assertEquals(ROOT, response.attributes().parentId());
			Assertions.assertEquals(ROOT, response.directoryAttributes().nodeId());
			Assertions.assertTrue(response.freeSpace().usableBytes() > 0);
			Assertions.assertEquals(response.attributes().nodeId(), lookup(ROOT, "new").nodeId());
			Assertions.assertEquals(target, ok(new ReadlinkRequest(response.attributes().nodeId()), ReadlinkResponse.class).target());
			Assertions.assertEquals(List.of("link", "new"), backingNames());
		}

		@Test
		@DisplayName("creating a link sets no permissions, so its target keeps its mode")
		public void testSymlinkLeavesTargetUntouched() throws IOException {
			List<Path> permissionsSet = new ArrayList<>();
			ops.beforeSettingPermissions = permissionsSet::add;
			Files.setPosixFilePermissions(outside, PosixFilePermissions.fromString("rwx-----x"));

			ok(new SymlinkRequest(ROOT, "new", outside.toString()), SymlinkResponse.class);

			Assertions.assertEquals(List.of(), permissionsSet);
			Assertions.assertEquals("rwx-----x", PosixFilePermissions.toString(Files.getPosixFilePermissions(outside)));
		}

		@Test
		@DisplayName("creating a link under an existing name yields EEXIST and leaves the entry as it is")
		public void testSymlinkExisting() throws IOException {
			assertStatus(Errno.EEXIST, new SymlinkRequest(ROOT, "link", "other"));

			Assertions.assertEquals(outside, Files.readSymbolicLink(root.resolve("link")));
		}

		@Test
		@DisplayName("a target of 1023 bytes is the longest a link is created with")
		public void testSymlinkWithLongTarget() throws IOException {
			ok(new SymlinkRequest(ROOT, "longest", "a".repeat(1023)), SymlinkResponse.class);

			assertStatus(Errno.ENAMETOOLONG, new SymlinkRequest(ROOT, "too long", "a".repeat(1024)));
			// 1022 characters that take 1024 bytes
			assertStatus(Errno.ENAMETOOLONG, new SymlinkRequest(ROOT, "too long", "\u00e4\u00e4" + "a".repeat(1020)));

			Assertions.assertEquals(List.of("link", "longest"), backingNames());
		}

		@Test
		@DisplayName("a link replaces a file")
		public void testRenameOverFile() throws IOException {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();

			RenameResponse response = ok(new RenameRequest(link, ROOT, "link", ROOT, "file.txt"), RenameResponse.class);

			Assertions.assertEquals(file, response.replacedAttributes().nodeId());
			Assertions.assertTrue(Files.isSymbolicLink(root.resolve("file.txt")));
			Assertions.assertFalse(Files.exists(root.resolve("link"), LinkOption.NOFOLLOW_LINKS));
		}

		@Test
		@DisplayName("a file that was swapped for a link is not opened through the link")
		public void testFileSwappedForLink() throws IOException {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();
			Files.delete(root.resolve("file.txt"));
			Files.createSymbolicLink(root.resolve("file.txt"), outside.resolve("secret.txt"));

			Assertions.assertInstanceOf(Failure.class, ops.handle(new ReadRequest(file, 0, 100)));
			Assertions.assertInstanceOf(Failure.class, ops.handle(new WriteRequest(file, 0, StandardCharsets.UTF_8.encode("public"))));
			Assertions.assertInstanceOf(Failure.class, ops.handle(new SetattrRequest(file, Messages.ATTRIBUTE_SIZE, 0, 0, EPOCH, EPOCH)));

			Assertions.assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
		}

		@ParameterizedTest(name = "{0}")
		@DisplayName("a mode set on an entry that was swapped for a link leaves the link's target untouched")
		@CsvSource({"FILE, secret.txt", "DIRECTORY, ."})
		public void testModeOfEntrySwappedForLink(NodeType type, String targetName) throws IOException {
			Path target = outside.resolve(targetName).normalize();
			long node = create(ROOT, "entry", type).nodeId();
			ok(new CloseRequest(node, 0), CloseResponse.class);
			Files.delete(root.resolve("entry"));
			Files.createSymbolicLink(root.resolve("entry"), target);
			Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));

			SetattrResponse response = ok(new SetattrRequest(node, Messages.ATTRIBUTE_MODE, 0, 0777, EPOCH, EPOCH), SetattrResponse.class);

			Assertions.assertEquals(0, response.applied());
			Assertions.assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)));
		}

		@Test
		@DisplayName("creating a file under the name of a link yields EEXIST and leaves the link's target absent")
		public void testCreateAtLink() throws IOException {
			Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("absent.txt"));

			assertStatus(Errno.EEXIST, new CreateRequest(ROOT, "dangling", NodeType.FILE, 0644));

			Assertions.assertFalse(Files.exists(outside.resolve("absent.txt")));
		}

		@Test
		@DisplayName("setattr leaves the link's target untouched")
		public void testSetattr() throws IOException {
			FileTime before = Files.getLastModifiedTime(outside);
			int valid = Messages.ATTRIBUTE_SIZE | Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_MODIFIED;

			SetattrResponse response = ok(new SetattrRequest(link, valid, 0, 0, EPOCH, EPOCH), SetattrResponse.class);

			Assertions.assertEquals(0, response.applied());
			Assertions.assertEquals(before, Files.getLastModifiedTime(outside));
			Assertions.assertTrue(Files.isReadable(outside));
		}

		@Test
		@DisplayName("removing a link deletes only the link")
		public void testRemove() throws IOException {
			ok(new RemoveRequest(link, ROOT, "link"), RemoveResponse.class);

			Assertions.assertFalse(Files.exists(root.resolve("link"), LinkOption.NOFOLLOW_LINKS));
			Assertions.assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
		}

		@Test
		@DisplayName("renaming a link moves the link itself")
		public void testRename() throws IOException {
			ok(new RenameRequest(link, ROOT, "link", ROOT, "moved"), RenameResponse.class);

			Assertions.assertTrue(Files.isSymbolicLink(root.resolve("moved")));
			Assertions.assertEquals("secret", Files.readString(outside.resolve("secret.txt")));
		}
	}

	@Nested
	@DisplayName("when reads fail after a change")
	public class FailingReadsAfterChange {

		private final IOException failure = new IOException("backend gone");
		private final HookedOperations.Hook failing = _ -> {
			throw failure;
		};

		@Test
		@DisplayName("a write still succeeds, with the size it established and unknown usable space")
		public void testWrite() throws IOException {
			backing("file.txt", "0123456789");
			Attributes last = lookup(ROOT, "file.txt");
			ops.beforeReadingAttributes = failing;
			ops.beforeReadingUsableSpace = failing;

			WriteResponse response = write(last.nodeId(), 8, "abcdef");

			Assertions.assertEquals(6, response.written());
			Assertions.assertEquals(14, response.attributes().size());
			Assertions.assertEquals(last.nodeId(), response.attributes().nodeId());
			Assertions.assertEquals(last.mode(), response.attributes().mode());
			Assertions.assertEquals(Messages.UNKNOWN_USABLE_BYTES, response.freeSpace().usableBytes());
			Assertions.assertEquals("01234567abcdef", Files.readString(root.resolve("file.txt")));
		}

		@Test
		@DisplayName("a write within the file keeps the last known size")
		public void testWriteWithinFile() throws IOException {
			backing("file.txt", "0123456789");
			long file = lookup(ROOT, "file.txt").nodeId();
			ops.beforeReadingAttributes = failing;

			Assertions.assertEquals(10, write(file, 2, "ab").attributes().size());
		}

		@Test
		@DisplayName("a truncation still succeeds, with the size it established")
		public void testTruncate() throws IOException {
			backing("file.txt", "0123456789");
			long file = lookup(ROOT, "file.txt").nodeId();
			ops.beforeReadingAttributes = failing;

			SetattrResponse response = ok(new SetattrRequest(file, Messages.ATTRIBUTE_SIZE, 4, 0, EPOCH, EPOCH), SetattrResponse.class);

			Assertions.assertEquals(4, response.attributes().size());
			Assertions.assertEquals("0123", Files.readString(root.resolve("file.txt")));
		}

		@Test
		@DisplayName("a create still succeeds, with the type and mode it established")
		public void testCreate() throws IOException {
			Attributes directory = getattr(ROOT);
			ops.beforeReadingAttributes = failing;
			ops.beforeReadingUsableSpace = failing;

			CreateResponse response = ok(new CreateRequest(ROOT, "new.txt", NodeType.FILE, 0640), CreateResponse.class);

			Assertions.assertEquals(List.of("new.txt"), backingNames());
			Assertions.assertEquals("new.txt", response.name());
			Assertions.assertEquals(NodeType.FILE, response.attributes().type());
			Assertions.assertEquals(0640, response.attributes().mode());
			Assertions.assertEquals(0, response.attributes().size());
			Assertions.assertEquals(ROOT, response.attributes().parentId());
			Assertions.assertEquals(state(directory), state(response.directoryAttributes()));
			// derived from what the change established, which is newer than any sample taken before
			Assertions.assertTrue(response.directoryAttributes().generation() > directory.generation());
			Assertions.assertEquals(Messages.UNKNOWN_USABLE_BYTES, response.freeSpace().usableBytes());
		}

		@Test
		@DisplayName("a symlink still succeeds, with the type it established and the length of its target")
		public void testSymlink() throws IOException {
			// the name's node is left from a file that is gone. Nothing of its last known attributes may reach the reply.
			Files.setPosixFilePermissions(backing("link", "stale"), PosixFilePermissions.fromString("rw-------"));
			lookup(ROOT, "link");
			Files.delete(root.resolve("link"));
			Attributes directory = getattr(ROOT);
			ops.beforeReadingAttributes = failing;
			ops.beforeReadingUsableSpace = failing;

			SymlinkResponse response = ok(new SymlinkRequest(ROOT, "link", "t\u00e4rget"), SymlinkResponse.class);

			Assertions.assertEquals("t\u00e4rget", Files.readSymbolicLink(root.resolve("link")).toString());
			Assertions.assertEquals("link", response.name());
			Assertions.assertEquals(NodeType.SYMLINK, response.attributes().type());
			Assertions.assertEquals(0644, response.attributes().mode());
			Assertions.assertEquals(7, response.attributes().size());
			Assertions.assertEquals(ROOT, response.attributes().parentId());
			Assertions.assertEquals(state(directory), state(response.directoryAttributes()));
			Assertions.assertEquals(Messages.UNKNOWN_USABLE_BYTES, response.freeSpace().usableBytes());
		}

		@Test
		@DisplayName("a rename still succeeds, with the last known attributes")
		public void testRename() throws IOException {
			backing("old.txt", "content");
			Attributes directory = getattr(ROOT);
			Attributes file = lookup(ROOT, "old.txt");
			ops.beforeReadingAttributes = path -> {
				if (Files.exists(root.resolve("new.txt"))) {
					throw failure;
				}
			};

			RenameResponse response = ok(new RenameRequest(file.nodeId(), ROOT, "old.txt", ROOT, "new.txt"), RenameResponse.class);

			Assertions.assertEquals(List.of("new.txt"), backingNames());
			Assertions.assertEquals("new.txt", response.name());
			Assertions.assertEquals(state(file), state(response.attributes()));
			Assertions.assertEquals(state(directory), state(response.sourceDirectoryAttributes()));
			Assertions.assertEquals(state(directory), state(response.destinationDirectoryAttributes()));
		}

		@Test
		@DisplayName("a create, a symlink and a rename still succeed when the stored name cannot be read, with the requested name")
		public void testStoredNameUnreadable() throws IOException {
			backing("old.txt", "content");
			long file = lookup(ROOT, "old.txt").nodeId();
			// once the entry is there: a rename looks for an entry under its new name before it moves
			ops.beforeResolvingRealPath = path -> {
				if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
					throw failure;
				}
			};

			Assertions.assertEquals("created.txt", ok(new CreateRequest(ROOT, "created.txt", NodeType.FILE, 0644), CreateResponse.class).name());
			Assertions.assertEquals("link", ok(new SymlinkRequest(ROOT, "link", "target"), SymlinkResponse.class).name());
			Assertions.assertEquals("new.txt", ok(new RenameRequest(file, ROOT, "old.txt", ROOT, "new.txt"), RenameResponse.class).name());

			Assertions.assertEquals(List.of("created.txt", "link", "new.txt"), backingNames());
		}

		@Test
		@DisplayName("a move to another directory still succeeds, with the new parent")
		public void testMoveToOtherDirectory() throws IOException {
			backing("file.txt", "content");
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			Attributes file = lookup(ROOT, "file.txt");
			ops.beforeReadingAttributes = path -> {
				if (Files.exists(root.resolve("dir/file.txt"))) {
					throw failure;
				}
			};

			RenameResponse response = ok(new RenameRequest(file.nodeId(), ROOT, "file.txt", directory, "file.txt"), RenameResponse.class);

			Assertions.assertEquals(directory, response.attributes().parentId());
			Assertions.assertEquals(file.nodeId(), response.attributes().nodeId());
			Assertions.assertEquals(file.size(), response.attributes().size());
		}

		@Test
		@DisplayName("a change of permissions and times still succeeds, with the values it established")
		public void testModeAndTimes() throws IOException {
			backing("file.txt", "0123456789");
			long file = lookup(ROOT, "file.txt").nodeId();
			// the entry's type is read before a mode is applied
			ops.beforeSettingPermissions = _ -> ops.beforeReadingAttributes = failing;
			int valid = Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_ACCESSED | Messages.ATTRIBUTE_MODIFIED;
			Timestamp accessed = new Timestamp(1600000000, 0);
			Timestamp modified = new Timestamp(1500000000, 0);

			SetattrResponse response = ok(new SetattrRequest(file, valid, 0, 0750, accessed, modified), SetattrResponse.class);

			Assertions.assertEquals(valid, response.applied());
			Assertions.assertEquals(0750, response.attributes().mode());
			Assertions.assertEquals(accessed, response.attributes().accessed());
			Assertions.assertEquals(modified, response.attributes().modified());
			Assertions.assertEquals(10, response.attributes().size());
			Assertions.assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("file.txt"))));
		}

		@Test
		@DisplayName("a change of times whose mode is refused still succeeds, with the times it established and the last known mode")
		public void testTimesWithRefusedMode() throws IOException {
			backing("file.txt", "0123456789");
			Attributes last = lookup(ROOT, "file.txt");
			ops.beforeSettingTimes = _ -> ops.beforeReadingAttributes = failing;
			ops.beforeSettingPermissions = path -> {
				throw new AccessDeniedException(path.toString());
			};
			Timestamp modified = new Timestamp(1500000000, 0);

			SetattrResponse response = ok(new SetattrRequest(last.nodeId(), Messages.ATTRIBUTE_MODE | Messages.ATTRIBUTE_MODIFIED, 0, 0200, EPOCH, modified), SetattrResponse.class);

			Assertions.assertEquals(Messages.ATTRIBUTE_MODIFIED, response.applied());
			Assertions.assertEquals(modified, response.attributes().modified());
			Assertions.assertEquals(last.mode(), response.attributes().mode());
		}

		@Test
		@DisplayName("a remove still succeeds, with the directory's last known attributes")
		public void testRemove() throws IOException {
			backing("file.txt", "content");
			Attributes directory = getattr(ROOT);
			Attributes file = lookup(ROOT, "file.txt");
			ops.beforeReadingAttributes = path -> {
				if (!Files.exists(root.resolve("file.txt"))) {
					throw failure;
				}
			};

			RemoveResponse response = ok(new RemoveRequest(file.nodeId(), ROOT, "file.txt"), RemoveResponse.class);

			Assertions.assertEquals(state(file), state(response.attributes()));
			Assertions.assertEquals(state(directory), state(response.directoryAttributes()));
		}
	}

	@Nested
	@DisplayName("the event log opt-out")
	public class EventLogOptOutEntries {

		private Attributes directory;
		private Attributes file;

		@BeforeEach
		public void setup() {
			directory = lookup(ROOT, ".fseventsd");
			file = lookup(directory.nodeId(), "no_log");
		}

		@Test
		@DisplayName("the root shows .fseventsd with an empty no_log in it and stores neither")
		public void testShownWithoutBeingStored() throws IOException {
			Assertions.assertEquals(NodeType.DIRECTORY, directory.type());
			Assertions.assertEquals(ROOT, directory.parentId());
			Assertions.assertEquals(NodeType.FILE, file.type());
			Assertions.assertEquals(0, file.size());
			Assertions.assertEquals(directory.nodeId(), file.parentId());
			Assertions.assertEquals(directory, getattr(directory.nodeId()));
			Assertions.assertEquals(file, getattr(file.nodeId()));
			assertStatus(Errno.ENOENT, new LookupRequest(directory.nodeId(), "ignore"));
			Assertions.assertEquals(List.of(), backingNames());
		}

		@Test
		@DisplayName("the root's listing leaves .fseventsd out, and its own listing holds no_log")
		public void testListings() throws IOException {
			backing("file.txt", "");

			Assertions.assertEquals(List.of(".", "..", "file.txt"), names(readdir(ROOT, 0, 0, false)));
			Assertions.assertEquals(List.of("file.txt"), names(readdir(ROOT, 0, 0, true)));
			Assertions.assertEquals(List.of( //
					new DirectoryEntry(".", NodeType.DIRECTORY, directory.nodeId(), 1, null), //
					new DirectoryEntry("..", NodeType.DIRECTORY, ROOT, 2, null), //
					new DirectoryEntry("no_log", NodeType.FILE, file.nodeId(), 3, null)), readdir(directory.nodeId(), 0, 0, false).entries());
			ReaddirResponse withAttributes = readdir(directory.nodeId(), 0, 0, true);
			Assertions.assertEquals(List.of(new DirectoryEntry("no_log", NodeType.FILE, file.nodeId(), 3, file)), withAttributes.entries());
			Assertions.assertEquals(List.of(), readdir(directory.nodeId(), 3, withAttributes.verifier(), true).entries());
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(directory.nodeId(), 4, withAttributes.verifier(), true));
			assertStatus(Messages.STATUS_INVALID_COOKIE, new ReaddirRequest(directory.nodeId(), 3, withAttributes.verifier() + 1, true));
		}

		@Test
		@DisplayName("an entry named .fseventsd in the mounted path is hidden and left alone")
		public void testHidesStoredEntry() throws IOException {
			Files.createDirectory(root.resolve(".fseventsd"));
			backing(".fseventsd/fseventsd-uuid", "uuid");

			Assertions.assertEquals(directory, lookup(ROOT, ".fseventsd"));
			assertStatus(Errno.ENOENT, new LookupRequest(directory.nodeId(), "fseventsd-uuid"));
			Assertions.assertEquals(List.of(".", ".."), names(readdir(ROOT, 0, 0, false)));
			Assertions.assertEquals("uuid", Files.readString(root.resolve(".fseventsd/fseventsd-uuid")));
		}

		@Test
		@DisplayName("both can be opened, no_log can be read, and both are still there after a close and a forget")
		public void testReading() {
			ok(new OpenRequest(directory.nodeId(), READ), OpenResponse.class);
			ok(new OpenRequest(file.nodeId(), READ), OpenResponse.class);

			Assertions.assertEquals("", read(file.nodeId(), 0, 100));
			ok(new CloseRequest(file.nodeId(), 0), CloseResponse.class);
			ok(new CloseRequest(directory.nodeId(), 0), CloseResponse.class);
			ok(new ForgetRequest(file.nodeId(), 1), ForgetResponse.class);
			ok(new ForgetRequest(directory.nodeId(), 1), ForgetResponse.class);
			Assertions.assertEquals(file, lookup(directory.nodeId(), "no_log"));
		}

		@Test
		@DisplayName("reading either as a link yields EINVAL")
		public void testReadlink() {
			assertStatus(Errno.EINVAL, new ReadlinkRequest(directory.nodeId()));
			assertStatus(Errno.EINVAL, new ReadlinkRequest(file.nodeId()));
		}

		@Test
		@DisplayName("refuses every change to the two and stores nothing under their names")
		public void testChangesRefused() throws IOException {
			long stored = create(ROOT, "file.txt", NodeType.FILE).nodeId();
			List<Request> changes = List.of( //
					new CreateRequest(directory.nodeId(), "new.txt", NodeType.FILE, 0644), //
					new SymlinkRequest(directory.nodeId(), "link", "no_log"), //
					new OpenRequest(file.nodeId(), WRITE), //
					new OpenRequest(file.nodeId(), READ | WRITE), //
					new WriteRequest(file.nodeId(), 0, ByteBuffer.allocate(1)), //
					new SetattrRequest(file.nodeId(), Messages.ATTRIBUTE_SIZE, 1, 0, EPOCH, EPOCH), //
					new SetattrRequest(directory.nodeId(), Messages.ATTRIBUTE_MODE, 0, 0700, EPOCH, EPOCH), //
					new RemoveRequest(file.nodeId(), directory.nodeId(), "no_log"), //
					new RemoveRequest(directory.nodeId(), ROOT, ".fseventsd"), //
					new RenameRequest(file.nodeId(), directory.nodeId(), "no_log", ROOT, "moved"), //
					new RenameRequest(directory.nodeId(), ROOT, ".fseventsd", ROOT, "moved"), //
					new RenameRequest(stored, ROOT, "file.txt", directory.nodeId(), "file.txt"), //
					new RenameRequest(stored, ROOT, "file.txt", ROOT, ".fseventsd"));

			assertStatus(Errno.EEXIST, new CreateRequest(ROOT, ".fseventsd", NodeType.DIRECTORY, 0700));
			assertStatus(Errno.EEXIST, new SymlinkRequest(ROOT, ".fseventsd", "file.txt"));
			for (Request change : changes) {
				assertStatus(Errno.EPERM, change);
			}

			Assertions.assertEquals(List.of("file.txt"), backingNames());
			Assertions.assertEquals(file, getattr(file.nodeId()));
			Assertions.assertEquals(directory, getattr(directory.nodeId()));
		}
	}

	@Nested
	@DisplayName("AppleDouble and .DS_Store names")
	public class HiddenEntries {

		@Test
		@DisplayName("lookup of a hidden name that the backing directory holds yields ENOENT")
		public void testLookup() throws IOException {
			backing("._a", "companion");
			backing(".DS_Store", "settings");

			assertStatus(Errno.ENOENT, new LookupRequest(ROOT, "._a"));
			assertStatus(Errno.ENOENT, new LookupRequest(ROOT, ".DS_Store"));
		}

		@Test
		@DisplayName("lookup of a hidden name in a removed directory or in a file yields ENOENT")
		public void testLookupWhereNoEntryCanBe() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			ok(new RemoveRequest(directory, ROOT, "dir"), RemoveResponse.class);
			backing("file.txt", "");
			long file = lookup(ROOT, "file.txt").nodeId();

			assertStatus(Errno.ENOENT, new LookupRequest(directory, "._a"));
			assertStatus(Errno.ENOENT, new LookupRequest(file, ".DS_Store"));
		}

		@Test
		@DisplayName("._ and .ds_store are names like any other")
		public void testNamesThatAreNotHidden() throws IOException {
			backing("._", "");
			backing(".ds_store", "");

			lookup(ROOT, "._");
			lookup(ROOT, ".ds_store");
			Assertions.assertEquals(Set.of("._", ".ds_store"), Set.copyOf(names(readdir(ROOT, 0, 0, true))));
		}

		@ParameterizedTest(name = "wantAttributes = {0}")
		@DisplayName("listings leave hidden names out")
		@ValueSource(booleans = {false, true})
		public void testListing(boolean wantAttributes) throws IOException {
			backing("._a", "");
			backing(".DS_Store", "");
			backing("visible.txt", "");

			Assertions.assertEquals(wantAttributes ? List.of("visible.txt") : List.of(".", "..", "visible.txt"), names(readdir(ROOT, 0, 0, wantAttributes)));
		}

		@Test
		@DisplayName("creating a file, a directory or a link under a hidden name yields EPERM and creates nothing")
		public void testCreate() throws IOException {
			assertStatus(Errno.EPERM, new CreateRequest(ROOT, "._a", NodeType.FILE, 0644));
			assertStatus(Errno.EPERM, new CreateRequest(ROOT, ".DS_Store", NodeType.FILE, 0644));
			assertStatus(Errno.EPERM, new CreateRequest(ROOT, "._d", NodeType.DIRECTORY, 0755));
			assertStatus(Errno.EPERM, new SymlinkRequest(ROOT, "._l", "target"));

			Assertions.assertEquals(List.of(), backingNames());
		}

		@Test
		@DisplayName("renaming onto a hidden name yields EPERM and moves nothing")
		public void testRenameOnto() throws IOException {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();

			assertStatus(Errno.EPERM, new RenameRequest(file, ROOT, "file.txt", ROOT, "._file.txt"));
			assertStatus(Errno.EPERM, new RenameRequest(file, ROOT, "file.txt", ROOT, ".DS_Store"));

			Assertions.assertEquals(List.of("file.txt"), backingNames());
		}

		@Test
		@DisplayName("removing a directory that holds only hidden files removes them with it")
		public void testRemoveDirectory() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			backing("dir/._a", "");
			backing("dir/.DS_Store", "");

			ok(new RemoveRequest(directory, ROOT, "dir"), RemoveResponse.class);

			Assertions.assertEquals(List.of(), backingNames());
		}

		@Test
		@DisplayName("removing a directory that also holds a visible entry yields ENOTEMPTY and deletes nothing")
		public void testRemoveDirectoryWithVisibleEntry() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			backing("dir/._a", "");
			backing("dir/visible.txt", "");

			assertStatus(Errno.ENOTEMPTY, new RemoveRequest(directory, ROOT, "dir"));

			Assertions.assertEquals(List.of("._a", "visible.txt"), backingNames(root.resolve("dir")));
		}

		@Test
		@DisplayName("removing a directory that holds a hidden directory yields ENOTEMPTY and deletes nothing")
		public void testRemoveDirectoryWithHiddenDirectory() throws IOException {
			long directory = create(ROOT, "dir", NodeType.DIRECTORY).nodeId();
			// not a ._ file: macOS's rmdir deletes those of a directory that holds nothing else before it finds that one of them is a directory
			backing("dir/.DS_Store", "");
			Files.createDirectory(root.resolve("dir/._d"));

			assertStatus(Errno.ENOTEMPTY, new RemoveRequest(directory, ROOT, "dir"));

			Assertions.assertEquals(List.of(".DS_Store", "._d"), backingNames(root.resolve("dir")));
		}

		@Test
		@DisplayName("a directory replaces a directory that holds only hidden files")
		public void testRenameOverDirectory() throws IOException {
			long source = create(ROOT, "source", NodeType.DIRECTORY).nodeId();
			backing("source/content.txt", "content");
			Attributes target = create(ROOT, "target", NodeType.DIRECTORY);
			// not a ._ file, which macOS's rmdir would delete by itself
			backing("target/.DS_Store", "");

			RenameResponse response = ok(new RenameRequest(source, ROOT, "source", ROOT, "target"), RenameResponse.class);

			Assertions.assertEquals(target.nodeId(), response.replacedAttributes().nodeId());
			Assertions.assertEquals(List.of("target"), backingNames());
			Assertions.assertEquals(List.of("content.txt"), backingNames(root.resolve("target")));
		}

		@Test
		@DisplayName("a file does not replace a directory that holds only hidden files, which keeps them")
		public void testRenameFileOverDirectory() throws IOException {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();
			create(ROOT, "target", NodeType.DIRECTORY);
			// not a ._ file, which macOS's rmdir would delete by itself
			backing("target/.DS_Store", "");

			assertStatus(Errno.ENOTEMPTY, new RenameRequest(file, ROOT, "file.txt", ROOT, "target"));

			Assertions.assertEquals(List.of("file.txt", "target"), backingNames());
			Assertions.assertEquals(List.of(".DS_Store"), backingNames(root.resolve("target")));
		}
	}

	@Nested
	@DisplayName("concurrently")
	@Timeout(60)
	public class Concurrently {

		/**
		 * How long a request that is meant to wait is given to show that it does.
		 */
		private static final long BLOCKED_MILLIS = 300;

		private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

		@AfterEach
		public void stopThreads() {
			threads.shutdownNow();
		}

		private Future<Response> async(Request request) {
			return threads.submit(() -> ops.handle(request));
		}

		private static void assertWaits(Future<?> future) {
			Assertions.assertThrows(TimeoutException.class, () -> future.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS));
		}

		private static <T extends Response> T done(Future<Response> future, Class<T> type) throws Exception {
			return Assertions.assertInstanceOf(type, future.get(10, TimeUnit.SECONDS));
		}

		/**
		 * @return A hook that blocks the first time it runs for the given path, and does nothing otherwise
		 */
		private static HookedOperations.Hook blockingOnce(Path path, CountDownLatch entered, CountDownLatch release) {
			return onlyAt(path, HookedOperations.blocking(entered, release));
		}

		/**
		 * @return A hook that runs the given one the first time it runs for the given path, and does nothing otherwise
		 */
		private static HookedOperations.Hook onlyAt(Path path, HookedOperations.Hook hook) {
			AtomicBoolean ran = new AtomicBoolean();
			return candidate -> {
				if (candidate.equals(path) && ran.compareAndSet(false, true)) {
					hook.run(candidate);
				}
			};
		}
		@Test
		@DisplayName("an operation whose node a rename moved before it took its locks takes them anew for the new path")
		public void testRevalidation() throws Exception {
			long directory = create(ROOT, "d", NodeType.DIRECTORY).nodeId();
			long file = create(directory, "f", NodeType.FILE).nodeId();

			Future<Response> getattr = movedBeforeLocking(new GetattrRequest(file), root.resolve("d/f"), hook -> ops.beforeReadingAttributes = onlyAt(root.resolve("e/f"), hook));

			done(getattr, GetattrResponse.class);
			Assertions.assertTrue(Files.exists(root.resolve("g/f")));
		}

		@Test
		@DisplayName("a read that changes the access time while a getattr samples the file replies with the later generation")
		public void testGenerationOfRead() throws Exception {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();
			ok(new OpenRequest(file, READ), OpenResponse.class);
			CountDownLatch sampling = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.afterReadingAttributes = blockingOnce(root.resolve("file.txt"), sampling, release);

			Future<Response> getattr = async(new GetattrRequest(file));
			Assertions.assertTrue(sampling.await(5, TimeUnit.SECONDS));
			Future<Response> read = async(new ReadRequest(file, 0, 10));

			assertWaits(read);
			release.countDown();
			long sampled = done(getattr, GetattrResponse.class).attributes().generation();
			Assertions.assertTrue(done(read, ReadResponse.class).attributes().generation() > sampled);
		}

		@Test
		@DisplayName("a create in a directory that a listing samples replies with a later generation of the directory than the listing")
		public void testGenerationOfListedDirectory() throws Exception {
			long directory = create(ROOT, "d", NodeType.DIRECTORY).nodeId();
			long sub = create(directory, "sub", NodeType.DIRECTORY).nodeId();
			CountDownLatch sampling = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.afterReadingAttributes = blockingOnce(root.resolve("d/sub"), sampling, release);

			Future<Response> listing = async(new ReaddirRequest(directory, 0, 0, true));
			Assertions.assertTrue(sampling.await(5, TimeUnit.SECONDS));
			Future<Response> created = async(new CreateRequest(sub, "file.txt", NodeType.FILE, 0644));

			assertWaits(created);
			release.countDown();
			long listed = done(listing, ReaddirResponse.class).entries().getFirst().attributes().generation();
			Assertions.assertTrue(done(created, CreateResponse.class).directoryAttributes().generation() > listed);
		}

		@Test
		@DisplayName("a lookup that overlaps the forget of its node replies with a node that stays known")
		public void testLookupOverlappingForget() throws Exception {
			backing("file.txt", "");
			long file = lookup(ROOT, "file.txt").nodeId();
			CountDownLatch held = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.afterHold = blockingOnce(root.resolve("file.txt"), held, release);

			Future<Response> lookup = async(new LookupRequest(ROOT, "file.txt"));
			Assertions.assertTrue(held.await(5, TimeUnit.SECONDS));
			ok(new ForgetRequest(file, 1), ForgetResponse.class);
			release.countDown();

			long found = done(lookup, LookupResponse.class).attributes().nodeId();
			Assertions.assertEquals(found, getattr(found).nodeId());
		}
		/**
		 * Blocks a request on the entry {@code a/f} before it takes its locks, moves the entry to {@code b/f} meanwhile, and expects ENOENT with the entry left in {@code b}.
		 *
		 * @param request Makes the request from the node id of the entry and of {@code a}
		 */
		private void assertMovedMeanwhile(BiFunction<Long, Long, Request> request) throws Exception {
			long a = create(ROOT, "a", NodeType.DIRECTORY).nodeId();
			long b = create(ROOT, "b", NodeType.DIRECTORY).nodeId();
			long file = create(a, "f", NodeType.FILE).nodeId();
			CountDownLatch locking = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.beforeLocking = blockingOnce(root.resolve("a/f"), locking, release);

			Future<Response> pending = async(request.apply(file, a));
			Assertions.assertTrue(locking.await(5, TimeUnit.SECONDS));
			ok(new RenameRequest(file, a, "f", b, "f"), RenameResponse.class);
			release.countDown();

			Assertions.assertEquals(new Failure(Errno.ENOENT), pending.get(10, TimeUnit.SECONDS));
			Assertions.assertTrue(Files.exists(root.resolve("b/f")));
		}

		@Test
		@DisplayName("a remove of an entry that a rename moved to another directory meanwhile yields ENOENT and removes nothing")
		public void testRemoveAfterMove() throws Exception {
			assertMovedMeanwhile((file, a) -> new RemoveRequest(file, a, "f"));
		}

		@Test
		@DisplayName("a rename of an entry that another rename moved to another directory meanwhile yields ENOENT and moves nothing")
		public void testRenameAfterMove() throws Exception {
			assertMovedMeanwhile((file, a) -> new RenameRequest(file, a, "f", ROOT, "g"));
		}
		/**
		 * Blocks a request before it takes its locks for the directory {@code d}, renames {@code d} to {@code e}, and lets the request go on until it reaches a path below {@code e}. A rename of {@code e} to {@code g} has to wait for it there, which it would not if the request had kept the locks for {@code d}.
		 *
		 * @param locked  The path whose lock the request is blocked before
		 * @param reached Installs the given hook where the request reaches the path below {@code e}
		 * @return The request, which has completed, as has the rename to {@code g} after it
		 */
		private Future<Response> movedBeforeLocking(Request request, Path locked, Consumer<HookedOperations.Hook> reached) throws Exception {
			long directory = lookup(ROOT, "d").nodeId();
			CountDownLatch locking = new CountDownLatch(1);
			CountDownLatch continueLocking = new CountDownLatch(1);
			CountDownLatch blocked = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.beforeLocking = blockingOnce(locked, locking, continueLocking);
			reached.accept(HookedOperations.blocking(blocked, release));

			Future<Response> pending = async(request);
			Assertions.assertTrue(locking.await(5, TimeUnit.SECONDS));
			ok(new RenameRequest(directory, ROOT, "d", ROOT, "e"), RenameResponse.class);
			continueLocking.countDown();
			Assertions.assertTrue(blocked.await(5, TimeUnit.SECONDS));
			Future<Response> rename = async(new RenameRequest(directory, ROOT, "e", ROOT, "g"));
			assertWaits(rename);
			release.countDown();
			done(rename, RenameResponse.class);
			return pending;
		}

		@Test
		@DisplayName("a lookup in a directory that a rename moved before the lookup took its locks takes them anew for the new path")
		public void testRevalidationOfLookup() throws Exception {
			long directory = create(ROOT, "d", NodeType.DIRECTORY).nodeId();
			create(directory, "f", NodeType.FILE);

			Future<Response> lookup = movedBeforeLocking(new LookupRequest(directory, "f"), root.resolve("d"), hook -> ops.beforeReadingAttributes = onlyAt(root.resolve("e/f"), hook));

			done(lookup, LookupResponse.class);
		}

		@Test
		@DisplayName("a remove of an entry whose directory a rename moved before the remove took its locks takes them anew for the new path")
		public void testRevalidationOfRemove() throws Exception {
			long directory = create(ROOT, "d", NodeType.DIRECTORY).nodeId();
			long file = create(directory, "f", NodeType.FILE).nodeId();

			Future<Response> remove = movedBeforeLocking(new RemoveRequest(file, directory, "f"), root.resolve("d"), hook -> ops.beforeReadingAttributes = onlyAt(root.resolve("e/f"), hook));

			done(remove, RemoveResponse.class);
			Assertions.assertFalse(Files.exists(root.resolve("g/f")));
		}

		@Test
		@DisplayName("a rename of an entry whose directory a rename moved before it took its locks takes them anew for the new path")
		public void testRevalidationOfRename() throws Exception {
			long directory = create(ROOT, "d", NodeType.DIRECTORY).nodeId();
			long file = create(directory, "f", NodeType.FILE).nodeId();

			Future<Response> rename = movedBeforeLocking(new RenameRequest(file, directory, "f", directory, "h"), root.resolve("d"), hook -> ops.beforeMoving = onlyAt(root.resolve("e/f"), hook));

			done(rename, RenameResponse.class);
			Assertions.assertTrue(Files.exists(root.resolve("g/h")));
		}

		@Test
		@DisplayName("a create in a directory that a rename moved before the create took its locks creates the entry at the new path")
		public void testRevalidationOfCreate() throws Exception {
			long directory = create(ROOT, "d", NodeType.DIRECTORY).nodeId();
			CountDownLatch locking = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.beforeLocking = blockingOnce(root.resolve("d"), locking, release);

			Future<Response> created = async(new CreateRequest(directory, "f", NodeType.FILE, 0644));
			Assertions.assertTrue(locking.await(5, TimeUnit.SECONDS));
			ok(new RenameRequest(directory, ROOT, "d", ROOT, "e"), RenameResponse.class);
			release.countDown();

			done(created, CreateResponse.class);
			Assertions.assertTrue(Files.exists(root.resolve("e/f")));
		}

		@Test
		@DisplayName("a write to a file whose lookup is sampling it waits for the sample and replies with a later generation")
		public void testGenerationOfLookup() throws Exception {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();
			ok(new OpenRequest(file, READ | WRITE), OpenResponse.class);
			CountDownLatch sampling = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.afterReadingAttributes = blockingOnce(root.resolve("file.txt"), sampling, release);

			Future<Response> lookup = async(new LookupRequest(ROOT, "file.txt"));
			Assertions.assertTrue(sampling.await(5, TimeUnit.SECONDS));
			Future<Response> written = async(new WriteRequest(file, 0, StandardCharsets.UTF_8.encode("more content")));

			assertWaits(written);
			release.countDown();
			long sampled = done(lookup, LookupResponse.class).attributes().generation();
			Assertions.assertTrue(done(written, WriteResponse.class).attributes().generation() > sampled);
		}

		@Test
		@DisplayName("a sample of the free space that waits to read it is older than one a change takes meanwhile")
		public void testGenerationOfFreeSpace() throws Exception {
			backing("file.txt", "");
			long file = lookup(ROOT, "file.txt").nodeId();
			CountDownLatch reading = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			Runnable blocking = HookedOperations.blockingOnce(reading, release);
			ops.beforeReadingUsableSpace = _ -> blocking.run();

			Future<Response> statfs = async(new StatfsRequest());
			Assertions.assertTrue(reading.await(5, TimeUnit.SECONDS));
			long changed = write(file, 0, "data").freeSpace().generation();
			release.countDown();

			Assertions.assertTrue(done(statfs, StatfsResponse.class).freeSpace().generation() < changed);
		}

		@Test
		@DisplayName("a write that waited while a forget removed its node yields ESTALE and leaves no channel open")
		public void testWriteAfterForget() throws Exception {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();
			CountDownLatch locking = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.beforeLocking = blockingOnce(root.resolve("file.txt"), locking, release);

			Future<Response> written = async(new WriteRequest(file, 0, StandardCharsets.UTF_8.encode("data")));
			Assertions.assertTrue(locking.await(5, TimeUnit.SECONDS));
			ok(new ForgetRequest(file, 1), ForgetResponse.class);
			release.countDown();

			Assertions.assertEquals(new Failure(Errno.ESTALE), written.get(10, TimeUnit.SECONDS));
			Assertions.assertEquals(List.of(), ops.openedChannels);
		}

		@Test
		@DisplayName("two reads of one file run at the same time")
		public void testReadsShareTheFile() throws Exception {
			backing("file.txt", "content");
			long file = lookup(ROOT, "file.txt").nodeId();
			CountDownLatch reading = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.channelWrapper = channel -> hooked(channel, Map.of(ChannelCall.READ, HookedOperations.blockingOnce(reading, release)));
			ok(new OpenRequest(file, READ), OpenResponse.class);

			Future<Response> first = async(new ReadRequest(file, 0, 10));
			Assertions.assertTrue(reading.await(5, TimeUnit.SECONDS));
			Future<Response> second = async(new ReadRequest(file, 0, 10));

			done(second, ReadResponse.class);
			release.countDown();
			done(first, ReadResponse.class);
		}

		@Test
		@DisplayName("a sync waits for a channel that is being closed")
		public void testSyncWaitsForClose() throws Exception {
			backing("file.txt", "");
			long file = lookup(ROOT, "file.txt").nodeId();
			CountDownLatch closing = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			ops.channelWrapper = channel -> hooked(channel, Map.of(ChannelCall.CLOSE, HookedOperations.blockingOnce(closing, release)));
			write(file, 0, "data");

			Future<Response> close = async(new CloseRequest(file, 0));
			Assertions.assertTrue(closing.await(5, TimeUnit.SECONDS));
			Future<Response> sync = async(new SyncRequest());

			assertWaits(sync);
			release.countDown();
			done(close, CloseResponse.class);
			done(sync, SyncResponse.class);
		}

		@Test
		@DisplayName("a sync that is forcing one channel when another starts to close waits for that close")
		public void testSyncStartedBeforeClose() throws Exception {
			backing("f", "");
			backing("g", "");
			Map<String, Long> files = Map.of("f", lookup(ROOT, "f").nodeId(), "g", lookup(ROOT, "g").nodeId());
			CountDownLatch forcing = new CountDownLatch(1);
			CountDownLatch continueForcing = new CountDownLatch(1);
			CountDownLatch closing = new CountDownLatch(1);
			CountDownLatch continueClosing = new CountDownLatch(1);
			Runnable blockingForce = HookedOperations.blockingOnce(forcing, continueForcing);
			Runnable blockingClose = HookedOperations.blockingOnce(closing, continueClosing);
			AtomicReference<String> forcedFirst = new AtomicReference<>();
			Set<String> blockingCloses = ConcurrentHashMap.newKeySet();
			for (String name : files.keySet()) {
				ops.channelWrapper = channel -> hooked(channel, Map.of(ChannelCall.FORCE, () -> {
					forcedFirst.compareAndSet(null, name);
					blockingForce.run();
				}, ChannelCall.CLOSE, () -> {
					if (blockingCloses.contains(name)) {
						blockingClose.run();
					}
				}));
				write(files.get(name), 0, "data");
			}

			Future<Response> sync = async(new SyncRequest());
			Assertions.assertTrue(forcing.await(5, TimeUnit.SECONDS));
			String other = forcedFirst.get().equals("f") ? "g" : "f";
			blockingCloses.add(other);
			Future<Response> close = async(new CloseRequest(files.get(other), 0));
			Assertions.assertTrue(closing.await(5, TimeUnit.SECONDS));
			continueForcing.countDown();

			assertWaits(sync);
			continueClosing.countDown();
			done(close, CloseResponse.class);
			done(sync, SyncResponse.class);
		}

		@Test
		@DisplayName("a rename in other directories leaves the listing of a directory and the ids it reported valid")
		public void testListingAcrossRename() throws IOException {
			long directory = create(ROOT, "d", NodeType.DIRECTORY).nodeId();
			for (int i = 0; i < 250; i++) {
				backing("d/" + "x".repeat(200) + i, "");
			}
			long source = create(ROOT, "e", NodeType.DIRECTORY).nodeId();
			long destination = create(ROOT, "f", NodeType.DIRECTORY).nodeId();
			long moved = create(source, "moved", NodeType.FILE).nodeId();
			ReaddirResponse first = readdir(directory, 0, 0, false);
			Assertions.assertTrue(first.more());

			ok(new RenameRequest(moved, source, "moved", destination, "moved"), RenameResponse.class);

			Assertions.assertFalse(readdir(directory, first.entries().getLast().nextCookie(), first.verifier(), false).entries().isEmpty());
			for (DirectoryEntry entry : first.entries()) {
				Assertions.assertEquals(entry.nodeId(), getattr(entry.nodeId()).nodeId());
			}
		}

		@Test
		@DisplayName("a listing whose page is being built is not dropped to make room for others, and the ids on the page stay valid")
		public void testPinnedListing() throws Exception {
			long directory = create(ROOT, "d", NodeType.DIRECTORY).nodeId();
			for (String name : List.of("a", "b", "c")) {
				backing("d/" + name, "");
			}
			CountDownLatch sampling = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger reads = new AtomicInteger();
			HookedOperations.Hook blocking = HookedOperations.blocking(sampling, release);
			// the second entry, so that one was reported before
			ops.afterReadingAttributes = path -> {
				if (path.getParent().equals(root.resolve("d")) && reads.incrementAndGet() == 2) {
					blocking.run(path);
				}
			};

			Future<Response> listing = async(new ReaddirRequest(directory, 0, 0, true));
			Assertions.assertTrue(sampling.await(5, TimeUnit.SECONDS));
			for (int i = 0; i < 16; i++) {
				readdir(ROOT, 0, 0, false);
			}
			release.countDown();

			ReaddirResponse page = done(listing, ReaddirResponse.class);
			Assertions.assertEquals(3, page.entries().size());
			for (DirectoryEntry entry : page.entries()) {
				Assertions.assertEquals(entry.nodeId(), getattr(entry.nodeId()).nodeId());
			}
		}

		@Test
		@DisplayName("a create over a node whose entry was deleted outside the volume closes that node's channel before it sets the new one")
		public void testCreateOverLeftoverNode() throws IOException {
			long first = create(ROOT, "file.txt", NodeType.FILE).nodeId();
			Files.delete(root.resolve("file.txt"));

			long second = create(ROOT, "file.txt", NodeType.FILE).nodeId();

			Assertions.assertEquals(first, second);
			Assertions.assertFalse(ops.openedChannels.get(0).isOpen());
			Assertions.assertTrue(ops.openedChannels.get(1).isOpen());
			write(second, 0, "new");
			Assertions.assertEquals("new", Files.readString(root.resolve("file.txt")));
		}

		@Test
		@DisplayName("a create over a node whose leftover channel fails to close still succeeds, and the new channel serves the node")
		public void testCreateOverLeftoverNodeWithFailingClose() throws IOException {
			ops.channelWrapper = channel -> hooked(channel, Map.of(ChannelCall.CLOSE, () -> {
				throw new UncheckedIOException(new IOException("disk on fire"));
			}));
			long first = create(ROOT, "file.txt", NodeType.FILE).nodeId();
			Files.delete(root.resolve("file.txt"));
			ops.channelWrapper = UnaryOperator.identity();

			long second = create(ROOT, "file.txt", NodeType.FILE).nodeId();

			Assertions.assertEquals(first, second);
			write(second, 0, "new");
			Assertions.assertEquals("new", Files.readString(root.resolve("file.txt")));
			Assertions.assertEquals("new", read(second, 0, 10));
		}

		@Test
		@DisplayName("random operations from many threads neither deadlock nor fail unexpectedly, and leave every linked node at an entry of its type")
		public void testStress() throws Exception {
			long[] directories = {create(ROOT, "d0", NodeType.DIRECTORY).nodeId(), create(ROOT, "d1", NodeType.DIRECTORY).nodeId()};
			create(directories[0], "sub", NodeType.DIRECTORY);
			List<String> names = List.of("a", "b", "c", "sub");
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
			List<Future<?>> workers = new ArrayList<>();
			for (int i = 0; i < 16; i++) {
				Random random = new Random(i);
				workers.add(threads.submit(() -> {
					while (System.nanoTime() < deadline) {
						step(random, directories, names);
					}
					return null;
				}));
			}
			for (Future<?> worker : workers) {
				worker.get(30, TimeUnit.SECONDS);
			}

			Assertions.assertEquals(List.of(), ops.unexpectedFailures);
			for (Node node : ops.heldNodes) {
				if (!node.unlinked && !node.forgotten) {
					BasicFileAttributes attributes = Files.readAttributes(node.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					Assertions.assertEquals(node.type, attributes.isDirectory() ? NodeType.DIRECTORY : NodeType.FILE, node.path.toString());
				}
			}
		}

		private void step(Random random, long[] directories, List<String> names) {
			long directory = directories[random.nextInt(directories.length)];
			String name = names.get(random.nextInt(names.size()));
			switch (random.nextInt(4)) {
				case 0 -> ops.handle(new LookupRequest(directory, name));
				case 1 -> listAll(directory);
				case 2 -> ops.handle(new CreateRequest(directory, name, random.nextBoolean() ? NodeType.FILE : NodeType.DIRECTORY, 0755));
				default -> {
					if (ops.handle(new LookupRequest(directory, name)) instanceof LookupResponse found) {
						long node = found.attributes().nodeId();
						long other = directories[random.nextInt(directories.length)];
						switch (random.nextInt(4)) {
							case 0 -> ops.handle(new WriteRequest(node, random.nextInt(100), StandardCharsets.UTF_8.encode("data")));
							case 1 -> ops.handle(new ReadRequest(node, 0, 100));
							case 2 -> ops.handle(new RenameRequest(node, directory, name, other, names.get(random.nextInt(names.size()))));
							default -> ops.handle(new RemoveRequest(node, directory, name));
						}
					}
				}
			}
		}

		private void listAll(long directory) {
			Response response = ops.handle(new ReaddirRequest(directory, 0, 0, true));
			while (response instanceof ReaddirResponse page && page.more()) {
				response = ops.handle(new ReaddirRequest(directory, page.entries().getLast().nextCookie(), page.verifier(), true));
			}
		}
	}

	@Nested
	@DisplayName("read-only")
	public class ReadOnly {

		private long file;

		@BeforeEach
		public void setup() throws IOException {
			backing("file.txt", "content");
			ops.close();
			ops = new HookedOperations(root, true);
			file = lookup(ROOT, "file.txt").nodeId();
		}

		@Test
		@DisplayName("rejects every mutating operation with EROFS and leaves the backing directory unchanged")
		public void testMutationsRejected() throws IOException {
			FileTime modified = Files.getLastModifiedTime(root.resolve("file.txt"));
			List<Request> mutations = List.of( //
					new SetattrRequest(file, Messages.ATTRIBUTE_SIZE, 0, 0, EPOCH, EPOCH), //
					new SetattrRequest(file, Messages.ATTRIBUTE_MODIFIED, 0, 0, EPOCH, EPOCH), //
					new CreateRequest(ROOT, "new.txt", NodeType.FILE, 0644), //
					new CreateRequest(ROOT, "dir", NodeType.DIRECTORY, 0755), //
					new SymlinkRequest(ROOT, "link", "file.txt"), //
					new RemoveRequest(file, ROOT, "file.txt"), //
					new RenameRequest(file, ROOT, "file.txt", ROOT, "renamed.txt"), //
					new WriteRequest(file, 0, ByteBuffer.allocate(1)), //
					new OpenRequest(file, WRITE), //
					new OpenRequest(file, READ | WRITE));

			for (Request mutation : mutations) {
				assertStatus(Errno.EROFS, mutation);
			}

			Assertions.assertEquals(List.of("file.txt"), backingNames());
			Assertions.assertEquals("content", Files.readString(root.resolve("file.txt")));
			Assertions.assertEquals(modified, Files.getLastModifiedTime(root.resolve("file.txt")));
		}

		@Test
		@DisplayName("still reads")
		public void testReading() {
			ok(new OpenRequest(file, READ), OpenResponse.class);

			Assertions.assertEquals("content", read(file, 0, 100));
			ok(new ReaddirRequest(ROOT, 0, 0, true), ReaddirResponse.class);
			ok(new SyncRequest(), SyncResponse.class);
		}
	}
}
