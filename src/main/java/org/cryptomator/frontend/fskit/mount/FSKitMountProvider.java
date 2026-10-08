package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.frontend.fskit.BridgeSession;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.cryptomator.integrations.mount.MountCapability.MOUNT_TO_EXISTING_DIR;
import static org.cryptomator.integrations.mount.MountCapability.READ_ONLY;
import static org.cryptomator.integrations.mount.MountCapability.UNMOUNT_FORCED;
import static org.cryptomator.integrations.mount.MountCapability.VOLUME_NAME;

/**
 * Mounts a file system on macOS using FSKit.
 * <p>
 * The file system is served by this JVM. An FSKit extension answers some operations itself and forwards the others to it.
 * The provider is supported on macOS 27 or later with an {@code aarch64} JVM, unless the JVM runs from an app that embeds no extension for the file system type. A JVM outside an app, as in tests, or in an app that cannot be checked counts as supported.
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

	private final ProcessHelper.Starter processStarter;
	private final SessionFactory sessionFactory;
	private final ExtensionCheck extensionCheck;

	public FSKitMountProvider() {
		this(ProcessBuilder::start, FSKitMountProvider::createSession, NativeExtensionCheck.SHARED);
	}

	static BridgeSession createSession(Path fileSystemRoot, boolean readOnly) throws IOException {
		return new BridgeSession(new FileSystemOperations(fileSystemRoot, readOnly), SESSION_CLOSE_TIMEOUT);
	}

	FSKitMountProvider(ProcessHelper.Starter processStarter, SessionFactory sessionFactory, ExtensionCheck extensionCheck) {
		this.processStarter = processStarter;
		this.sessionFactory = sessionFactory;
		this.extensionCheck = extensionCheck;
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
		return new FSKitMountBuilder(processStarter, sessionFactory, extensionCheck, fsType(), fileSystemRoot);
	}

	private static String fsType() {
		return System.getProperty(FS_TYPE_PROPERTY, DEFAULT_FS_TYPE);
	}

	@Override
	public Set<MountCapability> capabilities() {
		return EnumSet.of(MOUNT_TO_EXISTING_DIR, READ_ONLY, UNMOUNT_FORCED, VOLUME_NAME);
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
		private static final int MOUNT_TIMEOUT_SECONDS = 30;

		private final ProcessHelper.Starter processStarter;
		private final SessionFactory sessionFactory;
		private final ExtensionCheck extensionCheck;
		private final String fsType;
		private final Path fileSystemRoot;
		private Path mountPoint;
		private boolean readOnly;
		private String volumeName = "Untitled";

		public FSKitMountBuilder(ProcessHelper.Starter processStarter, SessionFactory sessionFactory, ExtensionCheck extensionCheck, String fsType, Path fileSystemRoot) {
			this.processStarter = processStarter;
			this.sessionFactory = sessionFactory;
			this.extensionCheck = extensionCheck;
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
			Objects.requireNonNull(mountPoint);
			checkExtensionSwitchedOn();
			Path realMountPoint;
			try {
				realMountPoint = mountPoint.toRealPath();
				if (!MountTable.read(processStarter).sourcesOfMountsAt(realMountPoint).isEmpty()) {
					throw new MountFailedException("Something is already mounted at " + mountPoint);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new MountFailedException(e);
			} catch (ProcessHelper.CommandFailedException e) {
				throw new MountFailedException("Failed to determine whether the mount point is in use: " + e.stderr, e);
			} catch (IOException | TimeoutException e) {
				throw new MountFailedException("Failed to determine whether the mount point is in use", e);
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
			boolean success = false;
			try {
				Files.createDirectory(rendezvousDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
				new Manifest(Messages.PROTOCOL_VERSION, session.port(), session.token(), volumeName).write(rendezvousDir);
				ProcessBuilder command = new ProcessBuilder(mountCommand(rendezvousDir));
				LOG.debug("Mounting {} using {}", fileSystemRoot.getFileSystem(), command.command());
				mountProcess = processStarter.start(command);
				ProcessHelper.waitForSuccess(mountProcess, MOUNT_TIMEOUT_SECONDS, "`mount`");
				if (!session.awaitHandshake(Duration.ZERO)) {
					throw new MountFailedException("`mount` succeeded, but the extension did not connect");
				}
				var mount = new FSKitMount(processStarter, session, rendezvousDir, mountPoint, realMountPoint);
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
					cleanUp(mountProcess, realMountPoint, session, rendezvousDir);
				}
			}
		}

		/**
		 * Fails if the extension is switched off, after opening System Settings, since only the user can switch it on.
		 * Without this check, {@code mount} would fail with a message about a disabled module.
		 */
		private void checkExtensionSwitchedOn() throws MountFailedException {
			switch (extensionCheck.status(fsType)) {
				case DISABLED -> {
					boolean opened = extensionCheck.openSettings();
					throw new MountFailedException("The FSKit extension is switched off. Switch it on in System Settings > General > Login Items & Extensions > File System Extensions, which " + (opened ? "has been opened" : "could not be opened") + ", and try again.");
				}
				case FAILED -> LOG.warn("Mounting without knowing whether the FSKit extension is switched on");
				default -> {
				}
			}
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

		private void cleanUp(@Nullable Process mountProcess, Path realMountPoint, BridgeSession session, Path rendezvousDir) {
			// an interrupted mount attempt is cleaned up all the same, which involves waiting
			boolean interrupted = Thread.interrupted();
			if (mountProcess != null) {
				try {
					mountProcess.destroyForcibly().waitFor();
					// a mount command that timed out, was interrupted or succeeded without the extension connecting may have left a mount behind
					FSKitMount.unmountIfMounted(processStarter, rendezvousDir, realMountPoint, true);
				} catch (InterruptedException e) {
					interrupted = true;
				} catch (ProcessHelper.CommandFailedException e) {
					LOG.warn("Failed to unmount {} after a failed mount attempt. {}: {}", mountPoint, e.getMessage(), e.stderr);
				} catch (IOException | TimeoutException e) {
					LOG.warn("Failed to unmount {} after a failed mount attempt", mountPoint, e);
				}
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
