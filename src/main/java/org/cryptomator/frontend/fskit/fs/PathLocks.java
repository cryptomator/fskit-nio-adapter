package org.cryptomator.frontend.fskit.fs;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The locks that keep concurrent operations on the entries below a root apart.
 * <p>
 * Each entry has three:
 * <ul>
 *     <li>its path lock, which an operation on the entry or below it holds for reading, and one that moves, replaces, creates or removes the entry holds for writing,</li>
 *     <li>for a directory, its entries lock, which guards the names in it: lookups and listings hold it for reading, a change to the names in it for writing. Unlike the path lock, it is never taken as an ancestor lock, so a change to the names in a directory does not wait for requests below it,</li>
 *     <li>its sampling lock, which is held around one read of the entry's attributes and their publication only.</li>
 * </ul>
 * Path and entries locks are taken only as one {@link #lock(Requirement...) lock set}, which adds the path locks of all ancestors for reading and acquires everything in one global order, so no two sets can deadlock as long as no thread holds two at once.
 * <p>
 * Locks are keyed by the entry's path with every name in NFC, so that a name's composed and decomposed forms share them. An entry's locks are created when first needed and dropped once their last user is done.
 */
final class PathLocks {

	/**
	 * A lock of a lock set.
	 *
	 * @param entries   Whether this is the entries lock of the directory, not its path lock
	 * @param exclusive Whether it is held for writing
	 */
	record Requirement(Path path, boolean entries, boolean exclusive) {

		static Requirement readPath(Path path) {
			return new Requirement(path, false, false);
		}

		static Requirement writePath(Path path) {
			return new Requirement(path, false, true);
		}

		static Requirement readEntries(Path path) {
			return new Requirement(path, true, false);
		}

		static Requirement writeEntries(Path path) {
			return new Requirement(path, true, true);
		}
	}

	/**
	 * Locks that are held until they are closed.
	 */
	interface Held extends AutoCloseable {

		Held NONE = () -> {};

		@Override
		void close();
	}

	private static final class Entry {

		final ReentrantReadWriteLock path = new ReentrantReadWriteLock();
		final ReentrantReadWriteLock entries = new ReentrantReadWriteLock();
		final ReentrantLock sampling = new ReentrantLock();
		int users;
	}

	private record Key(Path path, boolean entries) {
	}

	private static final Comparator<Key> ORDER = Comparator.<Key>comparingInt(key -> key.path().getNameCount()).thenComparing(key -> key.path().toString()).thenComparing(Key::entries);

	private final Path root;
	private final Map<Path, Entry> entriesByPath = new HashMap<>();

	PathLocks(Path root) {
		this.root = normalize(root);
	}

	/**
	 * Acquires a lock set: the given locks, merged at the strongest mode asked for each, and the path locks of all their ancestors up to the root for reading.
	 *
	 * @return The set, which releases its locks in reverse order when it is closed
	 */
	Held lock(Requirement... requirements) {
		TreeMap<Key, Boolean> set = new TreeMap<>(ORDER);
		for (Requirement requirement : requirements) {
			Path path = normalize(requirement.path());
			set.merge(new Key(path, requirement.entries()), requirement.exclusive(), Boolean::logicalOr);
			for (Path ancestor = path.getParent(); ancestor != null && ancestor.startsWith(root); ancestor = ancestor.getParent()) {
				set.merge(new Key(ancestor, false), false, Boolean::logicalOr);
			}
		}
		List<Path> used = new ArrayList<>(set.size());
		List<Lock> locks = new ArrayList<>(set.size());
		// every entry is counted as used before any lock is waited for, so that none is dropped and created afresh for a later set while someone waits for it
		synchronized (this) {
			set.forEach((key, exclusive) -> {
				Entry entry = use(key.path());
				ReentrantReadWriteLock lock = key.entries() ? entry.entries : entry.path;
				used.add(key.path());
				locks.add(exclusive ? lock.writeLock() : lock.readLock());
			});
		}
		locks.forEach(Lock::lock);
		return () -> {
			locks.reversed().forEach(Lock::unlock);
			release(used);
		};
	}

	/**
	 * Acquires the sampling lock of an entry.
	 */
	Held sample(Path path) {
		Path key = normalize(path);
		Entry entry;
		synchronized (this) {
			entry = use(key);
		}
		entry.sampling.lock();
		return () -> {
			entry.sampling.unlock();
			release(List.of(key));
		};
	}

	synchronized boolean isEmpty() {
		return entriesByPath.isEmpty();
	}

	private Entry use(Path key) {
		Entry entry = entriesByPath.computeIfAbsent(key, _ -> new Entry());
		entry.users++;
		return entry;
	}

	private synchronized void release(List<Path> keys) {
		for (Path key : keys) {
			Entry entry = entriesByPath.get(key);
			if (--entry.users == 0) {
				entriesByPath.remove(key);
			}
		}
	}

	private static Path normalize(Path path) {
		return path.getFileSystem().getPath(Normalizer.normalize(path.toString(), Normalizer.Form.NFC));
	}
}
