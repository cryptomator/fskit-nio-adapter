package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.frontend.fskit.BridgeSession;
import org.cryptomator.frontend.fskit.mount.FSKitMount.Placement;
import org.cryptomator.frontend.fskit.fs.FileSystemOperations;
import org.cryptomator.frontend.fskit.protocol.Manifest;
import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.integrations.common.OperatingSystem;
import org.cryptomator.integrations.common.Priority;
import org.cryptomator.integrations.mount.Mount;
import org.cryptomator.integrations.mount.MountBuilder;
import org.cryptomator.integrations.mount.MountCapability;
import org.cryptomator.integrations.mount.MountFailedException;
import org.cryptomator.integrations.mount.MountService;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.cryptomator.integrations.mount.MountCapability.MOUNT_TO_EXISTING_DIR;
import static org.cryptomator.integrations.mount.MountCapability.MOUNT_TO_SYSTEM_CHOSEN_PATH;
import static org.cryptomator.integrations.mount.MountCapability.READ_ONLY;
import static org.cryptomator.integrations.mount.MountCapability.UNMOUNT_FORCED;
import static org.cryptomator.integrations.mount.MountCapability.VOLUME_NAME;
import static org.cryptomator.integrations.mount.MountCapability.WHEN_TERMINATED;

/**
 * Mounts a file system on macOS using FSKit.
 * <p>
 * The file system is served by this JVM. An FSKit extension answers some operations itself and forwards the others to it.
 * The provider is supported on macOS 27 or later with an {@code aarch64} JVM, unless the JVM runs from an app that embeds no extension for the file system type. A JVM outside an app, as in tests, or in an app that cannot be checked counts as supported.
 * <p>
 * The provider mounts at a chosen existing directory with {@code /sbin/mount -F}. Without a mount point, FSKit mounts the volume in {@code /Volumes}. That needs an app that embeds the extension and holds the entitlement {@code com.apple.developer.fskit.mount}.
 * <p>
 * An app installs its extension switched off. Mounting with the extension switched off opens System Settings at File System Extensions and fails with a message that asks to switch it on and try again.
 *
 * @see <a href="https://developer.apple.com/documentation/fskit">FSKit documentation</a>
 */
@Priority(10)
@OperatingSystem(OperatingSystem.Value.MAC)
public class FSKitMountProvider implements MountService {

	private static final int MIN_OS_MAJOR_VERSION = 27;
	private static final String FS_TYPE_PROPERTY = "org.cryptomator.frontend.fskit.fsType";
	private static final String DEFAULT_FS_TYPE = "cryptomatorfs";
	private static final Duration SESSION_CLOSE_TIMEOUT = Duration.ofSeconds(10);
	static final int MOUNT_TIMEOUT_SECONDS = 30;

	private final ProcessHelper.Starter processStarter;
	private final SessionFactory sessionFactory;
	private final ExtensionCheck extensionCheck;
	private final VolumeMounter volumeMounter;

	public FSKitMountProvider() {
		this(ProcessBuilder::start, FSKitMountProvider::createSession, NativeExtensionCheck.SHARED, NativeVolumeMounter.SHARED);
	}

	static BridgeSession createSession(Path fileSystemRoot, boolean readOnly) throws IOException {
		return new BridgeSession(new FileSystemOperations(fileSystemRoot, readOnly), SESSION_CLOSE_TIMEOUT);
	}

	FSKitMountProvider(ProcessHelper.Starter processStarter, SessionFactory sessionFactory, ExtensionCheck extensionCheck, VolumeMounter volumeMounter) {
		this.processStarter = processStarter;
		this.sessionFactory = sessionFactory;
		this.extensionCheck = extensionCheck;
		this.volumeMounter = volumeMounter;
	}

	@Override
	public String displayName() {
		return "FSKit (Experimental)";
	}

	@Override
	public boolean isSupported() {
		return isSupported(System.getProperty("os.version"), System.getProperty("os.arch"));
	}

	/**
	 * Checks the app bundle only on a system that can run the extension. Asking FSKit takes too long for this check, so an extension that is embedded but not registered counts as supported.
	 */
	boolean isSupported(String osVersion, String osArch) {
		return isSupported(osVersion) && osArch.equals("aarch64") && extensionCheck.embedded(fsType()) != ExtensionCheck.Status.NOT_EMBEDDED;
	}

	static boolean isSupported(String osVersion) {
		try {
			return Integer.parseInt(osVersion.split("\\.")[0]) >= MIN_OS_MAJOR_VERSION;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	@Override
	public MountBuilder forFileSystem(Path fileSystemRoot) {
		return new FSKitMountBuilder(processStarter, sessionFactory, extensionCheck, volumeMounter, fsType(), fileSystemRoot);
	}

	private static String fsType() {
		return System.getProperty(FS_TYPE_PROPERTY, DEFAULT_FS_TYPE);
	}

	@Override
	public Set<MountCapability> capabilities() {
		return EnumSet.of(MOUNT_TO_EXISTING_DIR, MOUNT_TO_SYSTEM_CHOSEN_PATH, READ_ONLY, UNMOUNT_FORCED, VOLUME_NAME, WHEN_TERMINATED);
	}

	/**
	 * Creates the session of a mount. Tests substitute it.
	 */
	@FunctionalInterface
	interface SessionFactory {

		BridgeSession create(Path fileSystemRoot, boolean readOnly) throws IOException;
	}

	private static class FSKitMountBuilder implements MountBuilder {

		private static final Logger LOG = LoggerFactory.getLogger(FSKitMountBuilder.class);

		private final ProcessHelper.Starter processStarter;
		private final SessionFactory sessionFactory;
		private final ExtensionCheck extensionCheck;
		private final VolumeMounter volumeMounter;
		private final String fsType;
		private final Path fileSystemRoot;
		private @Nullable Path mountPoint;
		private boolean readOnly;
		private String volumeName = "Untitled";

		public FSKitMountBuilder(ProcessHelper.Starter processStarter, SessionFactory sessionFactory, ExtensionCheck extensionCheck, VolumeMounter volumeMounter, String fsType, Path fileSystemRoot) {
			this.processStarter = processStarter;
			this.sessionFactory = sessionFactory;
			this.extensionCheck = extensionCheck;
			this.volumeMounter = volumeMounter;
			this.fsType = fsType;
			this.fileSystemRoot = fileSystemRoot;
		}

		@Override
		public MountBuilder setMountpoint(Path mountPoint) {
			if (Files.isDirectory(mountPoint)) { // MOUNT_TO_EXISTING_DIR
				this.mountPoint = mountPoint;
			} else {
				throw new IllegalArgumentException("mount point must be an existing directory");
			}
			return this;
		}

		@Override
		public MountBuilder setReadOnly(boolean mountReadOnly) {
			this.readOnly = mountReadOnly;
			return this;
		}

		@Override
		public MountBuilder setVolumeName(String volumeName) {
			this.volumeName = volumeName;
			return this;
		}

		@Override
		public Mount mount() throws MountFailedException {
			ExtensionCheck.Status extensionStatus = checkExtensionSwitchedOn();
			Placement placement = mountPoint == null ? Placement.SYSTEM_CHOSEN_PATH : Placement.EXISTING_DIR;
			Path realMountPoint = null;
			switch (placement) {
				case EXISTING_DIR -> realMountPoint = checkMountPointFree();
				case SYSTEM_CHOSEN_PATH -> {
					if (extensionStatus == ExtensionCheck.Status.NOT_IN_APP || extensionStatus == ExtensionCheck.Status.NOT_EMBEDDED) {
						throw new MountFailedException("Mounting without a mount point needs an app that embeds the FSKit extension. Set a mount point.");
					}
				}
			}

			BridgeSession session;
			try {
				session = sessionFactory.create(fileSystemRoot, readOnly);
			} catch (IOException e) {
				throw new MountFailedException("Failed to start session", e);
			}

			// the directory that is passed to `mount` only tells the extension where to find the session; its name identifies this mount in the mount table
			Path rendezvousDir = Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString());
			Process mountProcess = null;
			Path unmountAt = null;
			boolean success = false;
			try {
				Files.createDirectory(rendezvousDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
				new Manifest(Messages.PROTOCOL_VERSION, session.port(), session.token(), volumeName).write(rendezvousDir);
				String mounter = switch (placement) {
					case EXISTING_DIR -> {
						ProcessBuilder command = new ProcessBuilder(mountCommand(rendezvousDir));
						LOG.debug("Mounting {} using {}", fileSystemRoot.getFileSystem(), command.command());
						mountProcess = processStarter.start(command);
						unmountAt = realMountPoint;
						ProcessHelper.waitForSuccess(mountProcess, MOUNT_TIMEOUT_SECONDS, "`mount`");
						yield "`mount`";
					}
					case SYSTEM_CHOSEN_PATH -> {
						LOG.debug("Mounting {} through FSKit", fileSystemRoot.getFileSystem());
						// FSKit creates the mount point as a new directory in `/Volumes`, so the path it reports is the one the mount table names
						unmountAt = realMountPoint = volumeMounter.mount(fsType, rendezvousDir, readOnly);
						yield "FSKit";
					}
				};
				if (!session.awaitHandshake(Duration.ZERO)) {
					throw new MountFailedException(mounter + " succeeded, but the extension did not connect");
				}
				if (session.hasEnded()) {
					throw new MountFailedException(mounter + " succeeded, but the extension disconnected again");
				}
				var mount = new FSKitMount(processStarter, session, rendezvousDir, mountPoint != null ? mountPoint : realMountPoint, realMountPoint, placement);
				success = true;
				return mount;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new MountFailedException(e);
			} catch (IOException e) {
				throw new MountFailedException(e);
			} catch (TimeoutException e) {
				throw new MountFailedException(e.getMessage() + ": " + availableStderr(mountProcess), e);
			} catch (ProcessHelper.CommandFailedException e) {
				throw new MountFailedException(e.getMessage() + ": " + e.stderr, e);
			} finally {
				if (!success) {
					cleanUp(mountProcess, unmountAt, placement, session, rendezvousDir);
				}
			}
		}

		/**
		 * @return The mount point with symbolic links resolved, as the mount table names it
		 */
		private Path checkMountPointFree() throws MountFailedException {
			try {
				Path realMountPoint = mountPoint.toRealPath();
				if (!MountTable.read(processStarter).sourcesOfMountsAt(realMountPoint).isEmpty()) {
					throw new MountFailedException("Something is already mounted at " + mountPoint);
				}
				return realMountPoint;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new MountFailedException(e);
			} catch (ProcessHelper.CommandFailedException e) {
				throw new MountFailedException("Failed to determine whether the mount point is in use: " + e.stderr, e);
			} catch (IOException | TimeoutException e) {
				throw new MountFailedException("Failed to determine whether the mount point is in use", e);
			}
		}

		/**
		 * Fails if the extension is switched off, after opening System Settings, since only the user can switch it on.
		 * Without this check, {@code mount} would fail with a message about a disabled module.
		 */
		private ExtensionCheck.Status checkExtensionSwitchedOn() throws MountFailedException {
			ExtensionCheck.Status status = extensionCheck.status(fsType);
			switch (status) {
				case DISABLED -> {
					boolean opened = extensionCheck.openSettings();
					throw new MountFailedException("The FSKit extension is switched off. Switch it on in System Settings > General > Login Items & Extensions > File System Extensions, which " + (opened ? "has been opened" : "could not be opened") + ", and try again.");
				}
				case FAILED -> LOG.warn("Mounting without knowing whether the FSKit extension is switched on");
				default -> {
				}
			}
			return status;
		}

		private List<String> mountCommand(Path rendezvousDir) {
			List<String> command = new ArrayList<>(List.of("/sbin/mount", "-F", "-t", fsType));
			if (readOnly) {
				// makes the system refuse changes itself, through an option the extension declares. The session enforces read-only as well.
				command.addAll(List.of("-o", "rdonly"));
			}
			command.addAll(List.of(rendezvousDir.toString(), mountPoint.toString()));
			return command;
		}

		/**
		 * @return What a process that is still running has written to its standard error so far. Reading to the end would wait for the process, and a destroyed process can no longer be read.
		 */
		private static String availableStderr(Process process) {
			try {
				InputStream stderr = process.getErrorStream();
				return new String(stderr.readNBytes(stderr.available()), StandardCharsets.UTF_8).strip();
			} catch (IOException e) {
				return "";
			}
		}

		/**
		 * @param unmountAt Where the attempt may have mounted, as the mount table names it, or {@code null} where it cannot have. A volume FSKit reports only after the timeout is unmounted by the library.
		 */
		private void cleanUp(@Nullable Process mountProcess, @Nullable Path unmountAt, Placement placement, BridgeSession session, Path rendezvousDir) {
			// an interrupted mount attempt is cleaned up all the same, which involves waiting
			boolean interrupted = Thread.interrupted();
			try {
				if (mountProcess != null) {
					mountProcess.destroyForcibly().waitFor();
				}
				if (unmountAt != null) {
					// a `mount` that timed out or was interrupted, or a mount the extension did not connect to, may have left a mount behind
					FSKitMount.unmountIfMounted(processStarter, rendezvousDir, unmountAt, placement, true);
				}
			} catch (InterruptedException e) {
				interrupted = true;
			} catch (ProcessHelper.CommandFailedException e) {
				LOG.warn("Failed to unmount {} after a failed mount attempt. {}: {}", unmountAt, e.getMessage(), e.stderr);
			} catch (IOException | TimeoutException e) {
				LOG.warn("Failed to unmount {} after a failed mount attempt", unmountAt, e);
			}
			try {
				session.close();
			} catch (IOException e) {
				LOG.warn("Closing the session of a failed mount attempt caused I/O error", e);
			}
			FSKitMount.deleteRendezvousDir(rendezvousDir);
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

}
