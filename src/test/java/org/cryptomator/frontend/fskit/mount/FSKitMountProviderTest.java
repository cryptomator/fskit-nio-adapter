package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.frontend.fskit.BridgeSession;
import org.cryptomator.frontend.fskit.TestBridgeClient;
import org.cryptomator.frontend.fskit.fs.Errno;
import org.cryptomator.frontend.fskit.fs.HookedOperations;
import org.cryptomator.frontend.fskit.protocol.Manifest;
import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.Failure;
import org.cryptomator.frontend.fskit.protocol.Messages.GetattrRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.cryptomator.integrations.common.IntegrationsLoader;
import org.cryptomator.integrations.common.OperatingSystem;
import org.cryptomator.integrations.mount.Mount;
import org.cryptomator.integrations.mount.MountBuilder;
import org.cryptomator.integrations.mount.MountCapability;
import org.cryptomator.integrations.mount.MountFailedException;
import org.cryptomator.integrations.mount.MountService;
import org.cryptomator.integrations.mount.Mountpoint;
import org.cryptomator.integrations.mount.UnmountFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Timeout(30)
public class FSKitMountProviderTest {

	private static final String OTHER_MOUNT = "/dev/disk3s5 on /System/Volumes/Data (apfs, local, journaled, nobrowse)";

	private Path fileSystemRoot;
	private Path mountPoint;

	/**
	 * What the substituted {@code /sbin/mount} prints when run without arguments.
	 */
	private final List<String> mountTable = new CopyOnWriteArrayList<>(List.of(OTHER_MOUNT));
	private final List<ProcessBuilder> commands = new CopyOnWriteArrayList<>();
	private final List<HookedOperations> operations = new CopyOnWriteArrayList<>();
	private final List<TestBridgeClient> clients = new CopyOnWriteArrayList<>();
	private volatile CommandHandler<MountCommand> mountCommandHandler = this::mountAndConnect;
	private volatile CommandHandler<ProcessBuilder> umountCommandHandler = this::unmount;
	private volatile CommandHandler<ProcessBuilder> mountTableHandler = _ -> exited(0, String.join("\n", mountTable), "");
	private volatile MountCommand lastMountCommand;
	private volatile Duration sessionCloseTimeout = Duration.ofSeconds(10);

	private FSKitMountProvider provider;

	/**
	 * @param rendezvousDir The directory passed to {@code mount}
	 * @param port          The port in the manifest, read while the manifest still exists
	 */
	private record MountCommand(ProcessBuilder command, Path rendezvousDir, int port) {
	}

	@FunctionalInterface
	private interface CommandHandler<T> {

		Process start(T command) throws IOException;
	}

	@BeforeEach
	public void setup(@TempDir Path tmpDir) throws IOException {
		fileSystemRoot = Files.createDirectory(tmpDir.resolve("root"));
		mountPoint = Files.createDirectory(tmpDir.resolve("mnt"));
		provider = new FSKitMountProvider(this::start, (root, readOnly) -> {
			HookedOperations ops = new HookedOperations(root, readOnly);
			operations.add(ops);
			return new BridgeSession(ops, sessionCloseTimeout);
		});
	}

	@AfterEach
	public void tearDown() throws IOException {
		for (TestBridgeClient client : clients) {
			client.close();
		}
	}

	/* substituted processes */

	private Process start(ProcessBuilder command) throws IOException {
		commands.add(command);
		List<String> args = command.command();
		if (args.equals(List.of("/sbin/mount"))) {
			return mountTableHandler.start(command);
		} else if (args.getFirst().equals("/sbin/mount")) {
			Path rendezvousDir = Path.of(args.get(args.size() - 2));
			lastMountCommand = new MountCommand(command, rendezvousDir, Manifest.read(rendezvousDir).port());
			return mountCommandHandler.start(lastMountCommand);
		} else if (args.getFirst().equals("/sbin/umount")) {
			return umountCommandHandler.start(command);
		} else {
			throw new IllegalArgumentException("Unexpected command " + args);
		}
	}

	private Process mountAndConnect(MountCommand mount) throws IOException {
		clients.add(TestBridgeClient.connect(mount.rendezvousDir()));
		addToMountTable(mount);
		return exited(0, "", "");
	}

	private void addToMountTable(MountCommand mount) throws IOException {
		mountTable.add("file://" + mount.rendezvousDir() + "/ on " + mountPoint.toRealPath() + " (cryptomatorfs, local, nodev, nosuid, noowners, noatime, fskit, mounted by me)");
	}

	/**
	 * Selects its target the way {@code umount} does: the last mount whose source equals the argument, else the last one mounted at the argument, without resolving any path.
	 */
	private Process unmount(ProcessBuilder command) {
		String target = command.command().getLast();
		List<String> lastFirst = new ArrayList<>(mountTable).reversed();
		var match = lastFirst.stream().filter(line -> line.startsWith(target + " on ")).findFirst() //
				.or(() -> lastFirst.stream().filter(line -> line.contains(" on " + target + " (")).findFirst());
		if (match.isPresent()) {
			mountTable.remove(match.get());
			return exited(0, "", "");
		} else {
			return exited(1, "", "umount: " + target + ": not currently mounted");
		}
	}

	private static Process exited(int exitCode, String stdout, String stderr) {
		try {
			Process process = Mockito.mock(Process.class);
			Mockito.when(process.waitFor(Mockito.any(Duration.class))).thenAnswer(_ -> interruptibly(true));
			Mockito.when(process.waitFor()).thenAnswer(_ -> interruptibly(exitCode));
			Mockito.when(process.exitValue()).thenReturn(exitCode);
			Mockito.when(process.inputReader(StandardCharsets.UTF_8)).thenReturn(new BufferedReader(new StringReader(stdout)));
			Mockito.when(process.errorReader(StandardCharsets.UTF_8)).thenReturn(new BufferedReader(new StringReader(stderr)));
			Mockito.when(process.destroyForcibly()).thenReturn(process);
			return process;
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * A process that does not exit by itself. Like a real one, it can no longer be read once it has been destroyed.
	 */
	private static Process hanging(String stderr) {
		try {
			Process process = Mockito.mock(Process.class);
			ClosableStream errorStream = new ClosableStream(stderr);
			Mockito.when(process.waitFor(Mockito.any(Duration.class))).thenAnswer(_ -> interruptibly(false));
			Mockito.when(process.waitFor()).thenAnswer(_ -> interruptibly(137));
			Mockito.when(process.getErrorStream()).thenReturn(errorStream);
			Mockito.when(process.errorReader(StandardCharsets.UTF_8)).thenReturn(new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8)));
			Mockito.when(process.destroyForcibly()).thenAnswer(_ -> {
				errorStream.close();
				return process;
			});
			return process;
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Process interruptedWhileWaitedFor() {
		try {
			Process process = hanging("");
			Mockito.doThrow(new InterruptedException()).when(process).waitFor(Mockito.any(Duration.class));
			return process;
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class ClosableStream extends ByteArrayInputStream {

		private boolean closed;

		ClosableStream(String content) {
			super(content.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public synchronized int read(byte[] buffer, int offset, int length) {
			if (closed) {
				throw new UncheckedIOException(new IOException("Stream closed"));
			}
			return super.read(buffer, offset, length);
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	/**
	 * Waits the way a real process is waited for: not at all for a thread that is interrupted.
	 */
	private static <T> T interruptibly(T result) throws InterruptedException {
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		return result;
	}

	/* helpers */

	private String realMountPoint() {
		try {
			return mountPoint.toRealPath().toString();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private MountBuilder builder() {
		return provider.forFileSystem(fileSystemRoot).setMountpoint(mountPoint).setVolumeName("Tresor");
	}

	private List<List<String>> commandsStartingWith(String executable) {
		return commands.stream().map(ProcessBuilder::command).filter(args -> args.getFirst().equals(executable) && args.size() > 1).toList();
	}

	private void assertNothingLeftBehind() {
		Assertions.assertFalse(Files.exists(lastMountCommand.rendezvousDir()), "rendezvous directory exists");
		Assertions.assertThrows(IOException.class, () -> new TestBridgeClient(lastMountCommand.port()).close(), "session still listens");
		for (HookedOperations ops : operations) {
			Assertions.assertEquals(0, ops.closed.getCount(), "session still open");
		}
	}

	/* tests */

	@Test
	@DisplayName("has exactly four capabilities")
	public void testCapabilities() {
		Assertions.assertEquals(Set.of(MountCapability.MOUNT_TO_EXISTING_DIR, MountCapability.READ_ONLY, MountCapability.UNMOUNT_FORCED, MountCapability.VOLUME_NAME), provider.capabilities());
	}

	@ParameterizedTest(name = "os.version {0} -> {1}")
	@DisplayName("is supported from macOS 27 on")
	@CsvSource(value = {"27.0, true", "27, true", "27.1.2, true", "28.0, true", "100.0, true", "26.4, false", "26.9.9, false", "15.5, false", "10.15.7, false", "'', false", "beta, false"})
	public void testIsSupported(String osVersion, boolean expected) {
		Assertions.assertEquals(expected, FSKitMountProvider.isSupported(osVersion));
	}

	@Nested
	@DisplayName("discovery")
	public class Discovery {

		@Test
		@DisplayName("the provider is registered as a MountService")
		public void testRegistration() {
			var loaded = IntegrationsLoader.loadSpecific(MountService.class, FSKitMountProvider.class.getName());

			Assertions.assertTrue(loaded.isPresent());
			Assertions.assertEquals("FSKit (Experimental)", loaded.get().displayName());
		}

		@Test
		@DisplayName("MountService.get() returns the provider exactly on macOS 27 and later")
		public void testSupportedProviders() {
			boolean expected = OperatingSystem.Value.current() == OperatingSystem.Value.MAC && FSKitMountProvider.isSupported(System.getProperty("os.version"));

			boolean found = MountService.get().anyMatch(service -> service.getClass().getName().equals(FSKitMountProvider.class.getName()));

			Assertions.assertEquals(expected, found);
		}
	}

	@Nested
	@DisplayName("builder")
	public class Builder {

		@Test
		@DisplayName("rejects a mount point that does not exist")
		public void testMissingMountPoint() {
			MountBuilder builder = provider.forFileSystem(fileSystemRoot);

			Assertions.assertThrows(IllegalArgumentException.class, () -> builder.setMountpoint(mountPoint.resolve("missing")));
		}

		@Test
		@DisplayName("rejects a mount point that is a file")
		public void testFileAsMountPoint() throws IOException {
			MountBuilder builder = provider.forFileSystem(fileSystemRoot);
			Path file = Files.createFile(mountPoint.resolve("file"));

			Assertions.assertThrows(IllegalArgumentException.class, () -> builder.setMountpoint(file));
		}

		@Test
		@DisplayName("refuses to mount without a mount point")
		public void testNoMountPoint() {
			MountBuilder builder = provider.forFileSystem(fileSystemRoot);

			Assertions.assertThrows(NullPointerException.class, builder::mount);
			Assertions.assertEquals(List.of(), commands);
		}
	}

	@Nested
	@DisplayName("mount")
	public class Mounting {

		@Test
		@DisplayName("passes an owner-only directory holding the manifest to the mount command")
		public void testMountCommand() throws MountFailedException, IOException, UnmountFailedException {
			List<Object> seenByMountCommand = new ArrayList<>();
			mountCommandHandler = mount -> {
				seenByMountCommand.add(PosixFilePermissions.toString(Files.getPosixFilePermissions(mount.rendezvousDir())));
				seenByMountCommand.add(PosixFilePermissions.toString(Files.getPosixFilePermissions(mount.rendezvousDir().resolve("manifest"))));
				seenByMountCommand.add(Manifest.read(mount.rendezvousDir()).volumeName());
				return mountAndConnect(mount);
			};

			try (Mount mount = builder().mount()) {
				Path rendezvousDir = lastMountCommand.rendezvousDir();
				Assertions.assertEquals(List.of("/sbin/mount", "-F", "-t", "cryptomatorfs", rendezvousDir.toString(), mountPoint.toString()), lastMountCommand.command().command());
				Assertions.assertEquals(Path.of(System.getProperty("java.io.tmpdir")), rendezvousDir.getParent());
				Assertions.assertDoesNotThrow(() -> UUID.fromString(rendezvousDir.getFileName().toString()));
				Assertions.assertEquals(List.of("rwx------", "rw-------", "Tresor"), seenByMountCommand);
				Assertions.assertEquals(Mountpoint.forPath(mountPoint), mount.getMountpoint());
				Assertions.assertTrue(Files.isDirectory(rendezvousDir));
			}
		}

		@Test
		@DisplayName("uses the file system type from the system property")
		public void testFsTypeProperty() throws MountFailedException, UnmountFailedException, IOException {
			System.setProperty("org.cryptomator.frontend.fskit.fsType", "testfs");
			try (Mount _ = builder().mount()) {
				Assertions.assertEquals(List.of("/sbin/mount", "-F", "-t", "testfs"), lastMountCommand.command().command().subList(0, 4));
			} finally {
				System.clearProperty("org.cryptomator.frontend.fskit.fsType");
			}
		}

		@Test
		@DisplayName("a read-only mount passes the read-only option and serves a read-only session")
		public void testReadOnly() throws MountFailedException, IOException, UnmountFailedException {
			try (Mount _ = builder().setReadOnly(true).mount()) {
				Assertions.assertEquals(List.of("/sbin/mount", "-F", "-t", "cryptomatorfs", "-o", "rdonly", lastMountCommand.rendezvousDir().toString(), mountPoint.toString()), lastMountCommand.command().command());
				Assertions.assertEquals(new Failure(Errno.EROFS), clients.getFirst().request(new CreateRequest(Messages.ROOT_NODE_ID, "file.txt", NodeType.FILE, 0644)));
			}
		}

		@Test
		@DisplayName("rejects a mount point where something is mounted and leaves that mount alone")
		public void testOccupiedMountPoint() throws IOException {
			mountTable.add("//user@server/share on " + mountPoint.toRealPath() + " (smbfs, nodev, nosuid, mounted by me)");

			Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			Assertions.assertEquals(List.of(List.of("/sbin/mount")), commands.stream().map(ProcessBuilder::command).toList());
			Assertions.assertEquals(List.of(), operations);
		}

		@Test
		@DisplayName("refuses to mount when the mount table cannot be read")
		public void testMountTableUnreadable() {
			mountTableHandler = _ -> exited(1, "", "mount: table unavailable");

			MountFailedException e = Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			Assertions.assertTrue(e.getMessage().contains("mount: table unavailable"), e.getMessage());
			Assertions.assertEquals(List.of(List.of("/sbin/mount")), commands.stream().map(ProcessBuilder::command).toList());
			Assertions.assertEquals(List.of(), operations);
		}

		@Test
		@DisplayName("rejects an occupied mount point given through a symbolic link")
		public void testOccupiedMountPointThroughSymlink() throws IOException {
			Path link = Files.createSymbolicLink(mountPoint.resolveSibling("link"), mountPoint);
			mountTable.add("//user@server/share on " + mountPoint.toRealPath() + " (smbfs, nodev, nosuid, mounted by me)");
			MountBuilder builder = provider.forFileSystem(fileSystemRoot).setMountpoint(link);

			Assertions.assertThrows(MountFailedException.class, builder::mount);

			Assertions.assertEquals(List.of(), commandsStartingWith("/sbin/umount"));
			Assertions.assertEquals(List.of(), commandsStartingWith("/sbin/mount"));
		}

		@Test
		@DisplayName("a mount point below an occupied one, or whose path an occupied one starts with, is free")
		public void testMountPointNextToOccupiedOnes() throws IOException, MountFailedException, UnmountFailedException {
			mountTable.add("//user@server/share on " + mountPoint.toRealPath().getParent() + " (smbfs, nodev, nosuid, mounted by me)");
			mountTable.add("//user@server/share on " + mountPoint.toRealPath() + "2 (smbfs, nodev, nosuid, mounted by me)");
			mountTable.add("//user@server/share on " + mountPoint.toRealPath() + "/sub (smbfs, nodev, nosuid, mounted by me)");

			builder().mount().close();

			Assertions.assertEquals(4, mountTable.size());
		}

		@Test
		@DisplayName("the default session factory hands the read-only flag to the session")
		public void testDefaultSessionFactory() throws IOException {
			try (BridgeSession writable = FSKitMountProvider.createSession(fileSystemRoot, false); //
				 BridgeSession readOnly = FSKitMountProvider.createSession(fileSystemRoot, true); //
				 TestBridgeClient writableClient = TestBridgeClient.connect(writable.port(), writable.token()); //
				 TestBridgeClient readOnlyClient = TestBridgeClient.connect(readOnly.port(), readOnly.token())) {
				Assertions.assertEquals(new Failure(Errno.EROFS), readOnlyClient.request(new CreateRequest(Messages.ROOT_NODE_ID, "file.txt", NodeType.FILE, 0644)));
				Assertions.assertInstanceOf(CreateResponse.class, writableClient.request(new CreateRequest(Messages.ROOT_NODE_ID, "file.txt", NodeType.FILE, 0644)));
			}
		}

		@Test
		@DisplayName("a failing mount command runs no umount and reports the command's error output")
		public void testMountCommandFails() {
			mountCommandHandler = _ -> exited(69, "", "mount: Unable to invoke task");

			MountFailedException e = Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			Assertions.assertTrue(e.getMessage().contains("mount: Unable to invoke task"), e.getMessage());
			Assertions.assertEquals(List.of(), commandsStartingWith("/sbin/umount"));
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a mount command that cannot be started leaves nothing behind")
		public void testMountCommandNotStarted() {
			mountCommandHandler = _ -> {
				throw new IOException("No such file or directory");
			};

			Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a mount command that times out is ended, and no umount runs if nothing got mounted")
		public void testMountCommandTimesOut() {
			Process[] process = new Process[1];
			mountCommandHandler = _ -> process[0] = hanging("mount: still waiting");

			MountFailedException e = Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			Assertions.assertTrue(e.getMessage().contains("mount: still waiting"), e.getMessage());
			Mockito.verify(process[0], Mockito.atLeastOnce()).destroyForcibly();
			Assertions.assertEquals(List.of(), commandsStartingWith("/sbin/umount"));
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a mount command that times out after mounting is ended and its mount removed")
		public void testMountCommandTimesOutAfterMounting() {
			Process[] process = new Process[1];
			mountCommandHandler = mount -> {
				addToMountTable(mount);
				return process[0] = hanging("");
			};

			Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			Mockito.verify(process[0], Mockito.atLeastOnce()).destroyForcibly();
			Assertions.assertEquals(List.of(List.of("/sbin/umount", "-f", "--", realMountPoint())), commandsStartingWith("/sbin/umount"));
			Assertions.assertEquals(List.of(OTHER_MOUNT), mountTable);
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("an interrupted mount is cleaned up all the same, and the thread stays interrupted")
		public void testInterruptedWhileMounting() throws InterruptedException {
			mountCommandHandler = mount -> {
				addToMountTable(mount);
				return interruptedWhileWaitedFor();
			};

			try {
				Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

				Assertions.assertTrue(Thread.currentThread().isInterrupted());
			} finally {
				Thread.interrupted();
			}
			Assertions.assertEquals(List.of(List.of("/sbin/umount", "-f", "--", realMountPoint())), commandsStartingWith("/sbin/umount"));
			Assertions.assertEquals(List.of(OTHER_MOUNT), mountTable);
			Assertions.assertTrue(operations.getFirst().closed.await(10, TimeUnit.SECONDS));
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("when the mount of a failed attempt cannot be removed, the session and the rendezvous directory still go")
		public void testForcedUnmountFailsAfterFailure() {
			mountCommandHandler = mount -> {
				addToMountTable(mount);
				return exited(0, "", "");
			};
			umountCommandHandler = _ -> exited(1, "", "umount(/mnt): Resource busy -- try 'diskutil unmount'");

			Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			Assertions.assertEquals(List.of(List.of("/sbin/umount", "-f", "--", realMountPoint())), commandsStartingWith("/sbin/umount"));
			Assertions.assertEquals(2, mountTable.size());
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a mount by someone else that appears at the mount point is left alone")
		public void testForeignMountAfterFailure() throws IOException {
			String foreignMount = "file:///somewhere/else/ on " + mountPoint.toRealPath() + " (cryptomatorfs, local, fskit)";
			mountCommandHandler = _ -> {
				mountTable.add(foreignMount);
				return exited(1, "", "mount: failed");
			};

			Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			Assertions.assertEquals(List.of(), commandsStartingWith("/sbin/umount"));
			Assertions.assertTrue(mountTable.contains(foreignMount));
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a mount command that succeeds without the extension connecting has its mount removed")
		public void testNoHandshake() {
			mountCommandHandler = mount -> {
				addToMountTable(mount);
				return exited(0, "", "");
			};

			Assertions.assertThrows(MountFailedException.class, () -> builder().mount());

			Assertions.assertEquals(List.of(List.of("/sbin/umount", "-f", "--", realMountPoint())), commandsStartingWith("/sbin/umount"));
			Assertions.assertEquals(List.of(OTHER_MOUNT), mountTable);
			assertNothingLeftBehind();
		}
	}

	@Nested
	@DisplayName("unmount")
	public class Unmounting {

		@Test
		@DisplayName("unmount runs umount on the resolved mount point, closes the session and deletes the rendezvous directory")
		public void testUnmount() throws MountFailedException, UnmountFailedException, IOException {
			Mount mount = builder().mount();

			mount.unmount();

			Assertions.assertEquals(List.of("/sbin/umount", "--", realMountPoint()), commands.getLast().command());
			assertNothingLeftBehind();

			mount.close();
			Assertions.assertEquals(1, commandsStartingWith("/sbin/umount").size());
		}

		@Test
		@DisplayName("a volume mounted through a symbolic link is unmounted by its resolved mount point")
		public void testUnmountThroughSymlink() throws MountFailedException, UnmountFailedException, IOException {
			Path link = Files.createSymbolicLink(mountPoint.resolveSibling("link"), mountPoint);
			Mount mount = provider.forFileSystem(fileSystemRoot).setMountpoint(link).mount();

			mount.unmount();

			Assertions.assertEquals(List.of("/sbin/umount", "--", realMountPoint()), commands.getLast().command());
			Assertions.assertEquals(List.of(OTHER_MOUNT), mountTable);
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a volume whose source is named like the mount point's last name is left alone")
		public void testSourceNamedLikeMountPoint() throws MountFailedException, UnmountFailedException {
			String namedLikeMountPoint = "mnt on /Volumes/elsewhere (somefs, local, mounted by me)";
			mountTable.add(namedLikeMountPoint);
			Mount mount = builder().mount();

			mount.unmount();

			Assertions.assertEquals(List.of(OTHER_MOUNT, namedLikeMountPoint), mountTable);
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("unmountForced runs umount -f")
		public void testUnmountForced() throws MountFailedException, UnmountFailedException, IOException {
			Mount mount = builder().mount();

			mount.unmountForced();

			Assertions.assertEquals(List.of("/sbin/umount", "-f", "--", realMountPoint()), commands.getLast().command());
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("close force-unmounts a volume that is still mounted")
		public void testClose() throws MountFailedException, UnmountFailedException, IOException {
			builder().mount().close();

			Assertions.assertEquals(List.of(List.of("/sbin/umount", "-f", "--", realMountPoint())), commandsStartingWith("/sbin/umount"));
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a volume that someone else unmounted is not unmounted again, and what is mounted in its place is left alone")
		public void testUnmountedBySomeoneElse() throws MountFailedException, UnmountFailedException, IOException {
			Mount mount = builder().mount();
			String foreignMount = "file:///somewhere/else/ on " + mountPoint.toRealPath() + " (cryptomatorfs, local, fskit)";
			mountTable.removeLast();
			mountTable.add(foreignMount);

			mount.unmountForced();

			Assertions.assertEquals(List.of(), commandsStartingWith("/sbin/umount"));
			Assertions.assertEquals(List.of(OTHER_MOUNT, foreignMount), mountTable);
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("while something else is mounted on top of the volume, unmounting fails and leaves both mounted")
		public void testUnmountBelowAnotherMount() throws MountFailedException, IOException, UnmountFailedException {
			String onTop = "//user@server/share on " + realMountPoint() + " (smbfs, nodev, nosuid, mounted by me)";
			try (Mount mount = builder().mount()) {
				mountTable.add(onTop);

				Assertions.assertThrows(UnmountFailedException.class, mount::unmountForced);

				Assertions.assertEquals(List.of(), commandsStartingWith("/sbin/umount"));
				Assertions.assertEquals(3, mountTable.size());
				Assertions.assertTrue(Files.isDirectory(lastMountCommand.rendezvousDir()));
				Assertions.assertFalse(clients.getFirst().request(new GetattrRequest(Messages.ROOT_NODE_ID)) instanceof Failure);
				mountTable.remove(onTop);
			}
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a volume that someone else unmounts while umount starts counts as unmounted")
		public void testUnmountedConcurrently() throws MountFailedException, UnmountFailedException {
			Mount mount = builder().mount();
			umountCommandHandler = _ -> exited(1, "", "umount: " + realMountPoint() + ": not currently mounted");

			mount.unmount();

			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("mounts and unmounts at a mount point whose name contains a parenthesis")
		public void testMountPointWithParenthesis() throws IOException, MountFailedException, UnmountFailedException {
			mountPoint = Files.createDirectory(mountPoint.resolveSibling("My Vault (1)"));
			Mount mount = builder().mount();

			mount.unmount();

			Assertions.assertEquals(List.of(List.of("/sbin/umount", "--", realMountPoint())), commandsStartingWith("/sbin/umount"));
			Assertions.assertEquals(List.of(OTHER_MOUNT), mountTable);
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("when the mount table cannot be read, unmounting fails and keeps the session and the rendezvous directory")
		public void testUnmountWithUnreadableMountTable() throws MountFailedException, IOException, UnmountFailedException {
			try (Mount mount = builder().mount()) {
				CommandHandler<ProcessBuilder> readable = mountTableHandler;
				mountTableHandler = _ -> exited(1, "", "mount: table unavailable");

				Assertions.assertThrows(UnmountFailedException.class, mount::unmountForced);

				Assertions.assertEquals(List.of(), commandsStartingWith("/sbin/umount"));
				Assertions.assertTrue(Files.isDirectory(lastMountCommand.rendezvousDir()));
				Assertions.assertFalse(clients.getFirst().request(new GetattrRequest(Messages.ROOT_NODE_ID)) instanceof Failure);
				mountTableHandler = readable;
			}
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("a failing umount keeps the session and the rendezvous directory")
		public void testUmountFails() throws MountFailedException, IOException, UnmountFailedException {
			try (Mount mount = builder().mount()) {
				umountCommandHandler = _ -> exited(1, "", "umount(/mnt): Resource busy -- try 'diskutil unmount'");

				Assertions.assertThrows(UnmountFailedException.class, mount::unmount);

				Assertions.assertTrue(Files.isDirectory(lastMountCommand.rendezvousDir()));
				Assertions.assertFalse(clients.getFirst().request(new GetattrRequest(Messages.ROOT_NODE_ID)) instanceof Failure);
				umountCommandHandler = FSKitMountProviderTest.this::unmount;
			}
			assertNothingLeftBehind();
		}

		@Test
		@DisplayName("while an operation is running in the backend, unmounting fails and keeps the rendezvous directory; a retry succeeds once it has returned")
		public void testUnmountWithOutstandingCleanup() throws MountFailedException, IOException, InterruptedException, UnmountFailedException {
			sessionCloseTimeout = Duration.ofMillis(300);
			Mount mount = builder().mount();
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			operations.getFirst().beforeReadingAttributes = HookedOperations.blocking(entered, release);
			clients.getFirst().send(new GetattrRequest(Messages.ROOT_NODE_ID));
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));

			Assertions.assertThrows(UnmountFailedException.class, mount::unmountForced);

			Assertions.assertEquals(List.of(OTHER_MOUNT), mountTable);
			Assertions.assertTrue(Files.isDirectory(lastMountCommand.rendezvousDir()));

			release.countDown();
			Assertions.assertTrue(operations.getFirst().closed.await(10, TimeUnit.SECONDS));
			mount.unmountForced();

			Assertions.assertEquals(1, commandsStartingWith("/sbin/umount").size());
			Assertions.assertFalse(Files.exists(lastMountCommand.rendezvousDir()));
			mount.close();
			Assertions.assertEquals(1, commandsStartingWith("/sbin/umount").size());
		}
	}
}
