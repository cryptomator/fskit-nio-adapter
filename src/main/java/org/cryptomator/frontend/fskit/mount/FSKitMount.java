package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.frontend.fskit.BridgeSession;
import org.cryptomator.frontend.fskit.protocol.Manifest;
import org.cryptomator.integrations.mount.Mount;
import org.cryptomator.integrations.mount.Mountpoint;
import org.cryptomator.integrations.mount.UnmountFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;

final class FSKitMount implements Mount {

	private static final Logger LOG = LoggerFactory.getLogger(FSKitMount.class);
	private static final int UMOUNT_TIMEOUT_SECONDS = 10;

	private final ProcessHelper.Starter processStarter;
	private final BridgeSession session;
	private final Path rendezvousDir;
	private final Path mountpoint;
	private final Path realMountpoint;
	private boolean unmounted;
	// set before `umount` runs, since `umount` ends the session before this mount closes it, and cleared again if unmounting fails
	private volatile boolean sessionEndExpected;

	/**
	 * @param realMountpoint {@code mountpoint} with symbolic links resolved, as the mount table names it
	 */
	FSKitMount(ProcessHelper.Starter processStarter, BridgeSession session, Path rendezvousDir, Path mountpoint, Path realMountpoint) {
		this.processStarter = processStarter;
		this.session = session;
		this.rendezvousDir = rendezvousDir;
		this.mountpoint = mountpoint;
		this.realMountpoint = realMountpoint;
		session.ended().thenRun(this::reportUnexpectedEnd);
	}

	@Override
	public Mountpoint getMountpoint() {
		return Mountpoint.forPath(mountpoint);
	}

	@Override
	public void unmount() throws UnmountFailedException {
		unmount(false);
	}

	@Override
	public void unmountForced() throws UnmountFailedException {
		unmount(true);
	}

	private void unmount(boolean forced) throws UnmountFailedException {
		sessionEndExpected = true;
		boolean success = false;
		try {
			unmountIfMounted(processStarter, rendezvousDir, realMountpoint, forced);
			success = true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new UnmountFailedException(e);
		} catch (TimeoutException | IOException e) {
			throw new UnmountFailedException(e);
		} catch (ProcessHelper.CommandFailedException e) {
			LOG.warn("{}:\nSTDOUT: {}\nSTDERR: {}\n", e.getMessage(), e.stdout, e.stderr);
			throw new UnmountFailedException(e);
		} finally {
			if (!success) {
				sessionEndExpected = false;
				// a session that ended during the attempt was taken for the end of this unmount
				if (session.ended().toCompletableFuture().isDone()) {
					reportUnexpectedEnd();
				}
			}
		}
		try {
			// the caller closes the backing file system once this returns, so all channels must be closed by then
			session.close();
		} catch (IOException e) {
			throw new UnmountFailedException(e);
		}
		deleteRendezvousDir(rendezvousDir);
		unmounted = true;
	}

	@Override
	public void close() throws UnmountFailedException {
		if (!unmounted) {
			unmountForced();
		}
	}

	/**
	 * Reports a session that ended without an unmount through this mount, as when someone else unmounted the volume, its extension or {@code fskitd} ended, or the connection failed.
	 */
	private void reportUnexpectedEnd() {
		if (sessionEndExpected) {
			return;
		}
		try {
			boolean stillMounted = MountTable.read(processStarter).sourcesOfMountsAt(realMountpoint).stream().anyMatch(source -> namesRendezvousDir(source, rendezvousDir));
			if (stillMounted) {
				LOG.error("The volume at {} lost its connection and fails every operation until it is unmounted.", mountpoint);
			} else {
				LOG.warn("The volume at {} was unmounted by someone else, or its FSKit extension or fskitd ended. The mount point is a plain directory again: whatever is written to it lands on the local disk, not in the mounted file system.", mountpoint);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOG.warn("The session of the volume at {} ended unexpectedly. Interrupted while determining whether it is still mounted.", mountpoint);
		} catch (ProcessHelper.CommandFailedException e) {
			LOG.warn("The session of the volume at {} ended unexpectedly. Failed to determine whether it is still mounted. {}: {}", mountpoint, e.getMessage(), e.stderr);
		} catch (IOException | TimeoutException | RuntimeException e) {
			LOG.warn("The session of the volume at {} ended unexpectedly. Failed to determine whether it is still mounted.", mountpoint, e);
		}
	}

	/**
	 * Unmounts the volume of the session in {@code rendezvousDir}, unless it is no longer mounted. Whatever else is mounted at the mount point by now is left alone.
	 *
	 * @throws IOException If something else is mounted on top of the volume, which would be unmounted in its place
	 */
	static void unmountIfMounted(ProcessHelper.Starter processStarter, Path rendezvousDir, Path realMountpoint, boolean forced) throws IOException, TimeoutException, InterruptedException, ProcessHelper.CommandFailedException {
		List<String> sources = MountTable.read(processStarter).sourcesOfMountsAt(realMountpoint);
		if (sources.stream().noneMatch(source -> namesRendezvousDir(source, rendezvousDir))) {
			LOG.info("{} already unmounted. Nothing to do.", realMountpoint);
			return;
		}
		// `umount` takes the last mount whose source equals its argument, else the last one mounted at it, before it resolves any path. Given the mount point as the mount table names it, that is the volume only if it is the last one mounted there.
		if (!namesRendezvousDir(sources.getLast(), rendezvousDir)) {
			throw new IOException("Something else is mounted on top of " + realMountpoint);
		}
		String target = realMountpoint.toString();
		ProcessBuilder command = forced ? new ProcessBuilder("/sbin/umount", "-f", "--", target) : new ProcessBuilder("/sbin/umount", "--", target);
		try {
			ProcessHelper.waitForSuccess(processStarter.start(command), UMOUNT_TIMEOUT_SECONDS, forced ? "`umount -f`" : "`umount`");
		} catch (ProcessHelper.CommandFailedException e) {
			// unmounted by someone else since the mount table was read
			if (!e.stderr.contains("not currently mounted")) {
				throw e;
			}
			LOG.info("{} already unmounted. Nothing to do.", realMountpoint);
		}
	}

	// the name of the rendezvous directory is unique to its mount
	private static boolean namesRendezvousDir(String source, Path rendezvousDir) {
		return source.contains(rendezvousDir.getFileName().toString());
	}

	static void deleteRendezvousDir(Path rendezvousDir) {
		try {
			Manifest.delete(rendezvousDir);
			Files.deleteIfExists(rendezvousDir);
		} catch (IOException e) {
			LOG.warn("Failed to delete {}", rendezvousDir, e);
		}
	}
}
