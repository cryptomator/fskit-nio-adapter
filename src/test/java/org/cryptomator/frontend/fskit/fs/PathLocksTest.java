package org.cryptomator.frontend.fskit.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.cryptomator.frontend.fskit.fs.PathLocks.Requirement.readEntries;
import static org.cryptomator.frontend.fskit.fs.PathLocks.Requirement.readPath;
import static org.cryptomator.frontend.fskit.fs.PathLocks.Requirement.writeEntries;
import static org.cryptomator.frontend.fskit.fs.PathLocks.Requirement.writePath;

@Timeout(30)
public class PathLocksTest {

	private static final Path ROOT = Path.of("/root");
	/**
	 * How long a lock set that is meant to wait is given to show that it does.
	 */
	private static final long BLOCKED_MILLIS = 200;

	private final PathLocks locks = new PathLocks(ROOT);
	private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	public void tearDown() {
		threads.shutdownNow();
	}

	private static Path path(String relative) {
		return ROOT.resolve(relative);
	}

	/**
	 * Takes a lock set on another thread and releases it at once.
	 */
	private Future<?> lockAndRelease(PathLocks.Requirement... requirements) {
		return threads.submit(() -> locks.lock(requirements).close());
	}

	private static void assertWaits(Future<?> future) {
		Assertions.assertThrows(TimeoutException.class, () -> future.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS));
	}

	private static void assertDone(Future<?> future) throws ExecutionException, InterruptedException, TimeoutException {
		future.get(5, TimeUnit.SECONDS);
	}

	@Test
	@DisplayName("a path lock is shared between readers and exclusive to a writer")
	public void testModes() throws Exception {
		try (var _ = locks.lock(readPath(path("a")))) {
			assertDone(lockAndRelease(readPath(path("a"))));
			Future<?> writer = lockAndRelease(writePath(path("a")));
			assertWaits(writer);
		}
		try (var _ = locks.lock(writePath(path("a")))) {
			assertWaits(lockAndRelease(readPath(path("a"))));
		}
	}

	@Test
	@DisplayName("a lock set holds the path locks of the ancestors for reading and leaves their entries locks alone")
	public void testAncestors() throws Exception {
		try (var _ = locks.lock(readPath(path("a/b/c")))) {
			assertDone(lockAndRelease(readPath(path("a"))));
			assertDone(lockAndRelease(writeEntries(path("a")), writeEntries(path("a/b"))));
			assertDone(lockAndRelease(writePath(path("a/b/d"))));
			assertWaits(lockAndRelease(writePath(path("a"))));
			assertWaits(lockAndRelease(writePath(path("a/b"))));
			assertWaits(lockAndRelease(writePath(ROOT)));
		}
	}

	@Test
	@DisplayName("entries locks are shared between readers and exclusive to a writer, apart from the path lock")
	public void testEntries() throws Exception {
		try (var _ = locks.lock(readEntries(path("a")))) {
			assertDone(lockAndRelease(readEntries(path("a"))));
			assertDone(lockAndRelease(writePath(path("a/b"))));
			assertWaits(lockAndRelease(writeEntries(path("a"))));
		}
	}

	@Test
	@DisplayName("crossed and opposite renames from many threads all finish")
	public void testNoDeadlock() throws Exception {
		List<PathLocks.Requirement[]> renames = List.of(
				// /x to /y/z, racing /y to /x/w
				new PathLocks.Requirement[]{readPath(ROOT), writeEntries(ROOT), readPath(path("y")), writeEntries(path("y")), writePath(path("x")), writePath(path("y/z"))},
				new PathLocks.Requirement[]{readPath(ROOT), writeEntries(ROOT), readPath(path("x")), writeEntries(path("x")), writePath(path("y")), writePath(path("x/w"))},
				// a to b, racing b to a
				new PathLocks.Requirement[]{readPath(path("d")), writeEntries(path("d")), writePath(path("d/a")), writePath(path("d/b"))},
				new PathLocks.Requirement[]{readPath(path("d")), writeEntries(path("d")), writePath(path("d/b")), writePath(path("d/a"))},
				// /p/a to /q/b, racing /q/c to /p/d, each naming its source directory first
				new PathLocks.Requirement[]{readPath(path("p")), writeEntries(path("p")), readPath(path("q")), writeEntries(path("q")), writePath(path("p/a")), writePath(path("q/b"))},
				new PathLocks.Requirement[]{readPath(path("q")), writeEntries(path("q")), readPath(path("p")), writeEntries(path("p")), writePath(path("q/c")), writePath(path("p/d"))});
		List<Future<?>> threadsRenaming = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			PathLocks.Requirement[] rename = renames.get(i % renames.size());
			threadsRenaming.add(threads.submit(() -> {
				for (int j = 0; j < 2000; j++) {
					locks.lock(rename).close();
				}
			}));
		}
		for (Future<?> renaming : threadsRenaming) {
			assertDone(renaming);
		}
		Assertions.assertTrue(locks.isEmpty());
	}

	@Test
	@DisplayName("the table is empty once every lock set and sampling lock is released")
	public void testEmptyAfterRelease() {
		PathLocks.Held first = locks.lock(readPath(path("a/b")), writeEntries(path("a")));
		PathLocks.Held second = locks.lock(readPath(path("a/b")));
		PathLocks.Held sampling = locks.sample(path("a/b"));
		Assertions.assertFalse(locks.isEmpty());

		sampling.close();
		second.close();
		first.close();

		Assertions.assertTrue(locks.isEmpty());
	}

	@Test
	@DisplayName("a thread that waited for a lock and a later one never hold it together once the holder releases it")
	public void testWaiterKeepsTheLock() throws Exception {
		AtomicInteger holders = new AtomicInteger();
		AtomicInteger mostHolders = new AtomicInteger();
		Runnable exclusive = () -> {
			try (var _ = locks.lock(writePath(path("a")))) {
				mostHolders.accumulateAndGet(holders.incrementAndGet(), Math::max);
				Thread.sleep(BLOCKED_MILLIS);
				holders.decrementAndGet();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		};
		Future<?> waiter;
		try (var _ = locks.lock(writePath(path("a")))) {
			waiter = threads.submit(exclusive);
			assertWaits(waiter);
		}
		Future<?> later = threads.submit(exclusive);

		assertDone(waiter);
		assertDone(later);
		Assertions.assertEquals(1, mostHolders.get());
		Assertions.assertTrue(locks.isEmpty());
	}

	@Test
	@DisplayName("a name in NFD and its NFC form share their locks")
	public void testUnicodeForms() throws Exception {
		try (var _ = locks.lock(writePath(path("ä")))) {
			assertWaits(lockAndRelease(writePath(path("ä"))));
			assertWaits(lockAndRelease(readPath(path("ä/child"))));
		}
		CountDownLatch sampled = new CountDownLatch(1);
		try (var _ = locks.sample(path("ä"))) {
			Future<?> other = threads.submit(() -> {
				locks.sample(path("ä")).close();
				sampled.countDown();
			});
			assertWaits(other);
		}
		Assertions.assertTrue(sampled.await(5, TimeUnit.SECONDS));
	}
}
