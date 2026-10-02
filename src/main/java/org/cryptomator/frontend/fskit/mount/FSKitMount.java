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

class FSKitMount implements Mount {

	private static final Logger LOG = LoggerFactory.getLogger(FSKitMount.class);
	private static final int UMOUNT_TIMEOUT_SECONDS = 10;

	private final ProcessHelper.Starter processStarter;
	private final BridgeSession session;
	private final Path rendezvousDir;
	private final Path mountpoint;
	private final Path realMountpoint;
	private boolean unmounted;

	/**
	 * @param realMountpoint {@code mountpoint} with symbolic links resolved, as the mount table names it
	 */
	FSKitMount(ProcessHelper.Starter processStarter, BridgeSession session, Path rendezvousDir, Path mountpoint, Path realMountpoint) {
		this.processStarter = processStarter;
		this.session = session;
		this.rendezvousDir = rendezvousDir;
		this.mountpoint = mountpoint;
		this.realMountpoint = realMountpoint;
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
		try {
			unmountIfMounted(processStarter, rendezvousDir, realMountpoint, forced);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new UnmountFailedException(e);
		} catch (TimeoutException | IOException e) {
			throw new UnmountFailedException(e);
		} catch (ProcessHelper.CommandFailedException e) {
			LOG.warn("{}:\nSTDOUT: {}\nSTDERR: {}\n", e.getMessage(), e.stdout, e.stderr);
			throw new UnmountFailedException(e);
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
	 * Unmounts the volume of the session in {@code rendezvousDir}, unless it is no longer mounted. Whatever else is mounted at the mount point by now is left alone.
	 *
	 * @throws IOException If something else is mounted on top of the volume, which would be unmounted in its place
	 */
	static void unmountIfMounted(ProcessHelper.Starter processStarter, Path rendezvousDir, Path realMountpoint, boolean forced) throws IOException, TimeoutException, InterruptedException, ProcessHelper.CommandFailedException {
		// the name of the rendezvous directory is unique to this mount
		String mountId = rendezvousDir.getFileName().toString();
		List<String> sources = MountTable.read(processStarter).sourcesOfMountsAt(realMountpoint);
		if (sources.stream().noneMatch(source -> source.contains(mountId))) {
			LOG.info("{} already unmounted. Nothing to do.", realMountpoint);
			return;
		}
		// `umount` takes the last mount whose source equals its argument, else the last one mounted at it, before it resolves any path. Given the mount point as the mount table names it, that is the volume only if it is the last one mounted there.
		if (!sources.getLast().contains(mountId)) {
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

	static void deleteRendezvousDir(Path rendezvousDir) {
		try {
			Manifest.delete(rendezvousDir);
			Files.deleteIfExists(rendezvousDir);
		} catch (IOException e) {
			LOG.warn("Failed to delete {}", rendezvousDir, e);
		}
	}
}
