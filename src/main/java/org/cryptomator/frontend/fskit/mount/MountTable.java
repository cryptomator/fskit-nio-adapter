package org.cryptomator.frontend.fskit.mount;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * The output of {@code /sbin/mount}, which answers what is mounted where without touching any mounted file system.
 *
 * @param lines One line per mount, formed like {@code file:///rendezvous/dir/ on /mount/point (cryptomatorfs, local, fskit)}
 */
record MountTable(List<String> lines) {

	private static final int TIMEOUT_SECONDS = 10;

	static MountTable read(ProcessHelper.Starter processStarter) throws IOException, TimeoutException, InterruptedException, ProcessHelper.CommandFailedException {
		Process process = processStarter.start(new ProcessBuilder("/sbin/mount"));
		@SuppressWarnings("resource") List<String> lines = process.inputReader(StandardCharsets.UTF_8).lines().toList();
		ProcessHelper.waitForSuccess(process, TIMEOUT_SECONDS, "`mount` (listing mounts)");
		return new MountTable(lines);
	}

	/**
	 * @param mountPoint A real path, since {@code mount} prints mount points with symbolic links resolved
	 * @return What is mounted at {@code mountPoint}, as {@code mount} prints it: the source of each mount, oldest first
	 */
	List<String> sourcesOfMountsAt(Path mountPoint) {
		String suffix = " on " + mountPoint;
		return lines.stream() //
				.map(line -> line.substring(0, Math.max(0, line.lastIndexOf(" (")))) //
				.filter(sourceAndMountPoint -> sourceAndMountPoint.endsWith(suffix)) //
				.map(sourceAndMountPoint -> sourceAndMountPoint.substring(0, sourceAndMountPoint.length() - suffix.length())) //
				.toList();
	}
}
