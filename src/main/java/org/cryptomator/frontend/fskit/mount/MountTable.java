package org.cryptomator.frontend.fskit.mount;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The output of {@code /sbin/mount}, which answers what is mounted where without touching any mounted file system.
 *
 * @param lines One line per mount, formed like {@code file:///rendezvous/dir/ on /mount/point (cryptomatorfs, local, fskit)}
 */
record MountTable(List<String> lines) {

	private static final int TIMEOUT_SECONDS = 10;
	private static final String DESCRIPTION = "`mount` (listing mounts)";

	static MountTable read(ProcessHelper.Starter processStarter) throws IOException, TimeoutException, InterruptedException, ProcessHelper.CommandFailedException {
		Process process = processStarter.start(new ProcessBuilder("/sbin/mount"));
		// read on a thread of its own while waiting: waiting first would stall a listing that fills the pipe, and reading first would block without a timeout on one that never closes its output
		@SuppressWarnings("resource") FutureTask<List<String>> output = new FutureTask<>(() -> process.inputReader(StandardCharsets.UTF_8).lines().toList());
		Thread.ofVirtual().name("mount-table-reader").start(output);
		boolean success = false;
		try {
			ProcessHelper.waitForSuccess(process, TIMEOUT_SECONDS, DESCRIPTION);
			MountTable table = new MountTable(output.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			success = true;
			return table;
		} catch (TimeoutException e) {
			throw new TimeoutException(DESCRIPTION + " did not finish within " + TIMEOUT_SECONDS + "s");
		} catch (ExecutionException e) {
			throw e.getCause() instanceof UncheckedIOException unchecked ? unchecked.getCause() : new IOException("Failed to read the output of " + DESCRIPTION, e.getCause());
		} finally {
			if (!success) {
				// ends the reader as well
				process.destroyForcibly();
			}
		}
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
