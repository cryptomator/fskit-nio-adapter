package org.cryptomator.frontend.fskit.fs;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A cache of the directory listings that enumerations page through.
 * <p>
 * A listing is the names its directory held when it was taken, and its verifier is a fingerprint of them. So a listing the cache has dropped can be taken again: if the directory still gives the same verifier, every cookie means what it meant before. A change in a directory leaves its cached listings as they are: a listing in progress shows no entry created since, and leaves out an entry that is gone.
 * <p>
 * The cache keeps the names of its listings, and the nodes that exist only because a listing reported them, within a budget of bytes. Over it, it first removes such nodes and then drops the listings used least recently. A listing that a page is built from is pinned until the page is done. A pin keeps the listing, and the listed nodes of its directory, whose ids the page reports. The listing used most recently stays as well, whatever its size.
 * <p>
 * Every instance method holds the cache's monitor, inside which it may take the node table's.
 */
final class DirectorySnapshots {

	private static final Logger LOG = LoggerFactory.getLogger(DirectorySnapshots.class);
	// Upper bounds of the heap each takes. On JDK 25, a name of 21 characters took 96 bytes, which these charge as 142. A listed node that a page has served took 591.
	private static final long BYTES_PER_LISTING = 128;
	private static final long BYTES_PER_NAME = 100;
	private static final long BYTES_PER_CHAR = 2;
	private static final long BYTES_PER_LISTED_NODE = 600;

	/**
	 * @param names The listing, starting with "." and ".."
	 */
	record Snapshot(long directoryId, long verifier, List<String> names) {
	}

	// equal names give equal verifiers in different directories
	private record Key(long directoryId, long verifier) {
	}

	private final NodeTable nodes;
	private final long budget;
	// in access order, so that the listings used least recently come first
	private final LinkedHashMap<Key, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true);
	private final Map<Key, Integer> pins = new HashMap<>();
	private long bytesOfNames;

	/**
	 * @param budget The bytes that the names of the listings and the nodes that were only listed may take together
	 */
	DirectorySnapshots(NodeTable nodes, long budget) {
		this.nodes = nodes;
		this.budget = budget;
	}

	/**
	 * @param names A listing, starting with "." and ".."
	 * @return A fingerprint of the names in their order, which is never 0
	 */
	static long verifierOf(List<String> names) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			ByteBuffer length = ByteBuffer.allocate(Integer.BYTES);
			for (String name : names) {
				byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
				digest.update(length.putInt(0, bytes.length));
				length.rewind();
				digest.update(bytes);
			}
			long verifier = ByteBuffer.wrap(digest.digest()).getLong();
			return verifier != 0 ? verifier : 1;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Takes a listing into the cache, whether it is new or one the cache had dropped.
	 *
	 * @param verifier The {@link #verifierOf(List) fingerprint} of the names
	 * @return The listing, pinned until it is {@link #unpin(Snapshot) unpinned}. If the cache already holds a listing of that directory with that verifier, it returns that one.
	 */
	synchronized Snapshot add(long directoryId, long verifier, List<String> names) {
		Key key = new Key(directoryId, verifier);
		Snapshot snapshot = snapshots.get(key);
		if (snapshot == null) {
			snapshot = new Snapshot(directoryId, verifier, names);
			snapshots.put(key, snapshot);
			bytesOfNames += bytesOf(names);
		}
		pin(key);
		evict();
		return snapshot;
	}

	/**
	 * @return The listing, pinned until it is {@link #unpin(Snapshot) unpinned}, or {@code null} if the cache holds none of that directory with that verifier
	 */
	synchronized @Nullable Snapshot get(long directoryId, long verifier) {
		Key key = new Key(directoryId, verifier);
		Snapshot snapshot = snapshots.get(key);
		if (snapshot == null) {
			return null;
		}
		pin(key);
		evict();
		return snapshot;
	}

	synchronized void unpin(Snapshot snapshot) {
		pins.computeIfPresent(new Key(snapshot.directoryId(), snapshot.verifier()), (_, count) -> count > 1 ? count - 1 : null);
		evict();
	}

	private void pin(Key key) {
		pins.merge(key, 1, Integer::sum);
	}

	private static long bytesOf(List<String> names) {
		long bytes = BYTES_PER_LISTING + BYTES_PER_NAME * names.size();
		for (String name : names) {
			bytes += BYTES_PER_CHAR * name.length();
		}
		return bytes;
	}

	/**
	 * @return The bytes by which the names and the listed nodes exceed the budget, which is not positive within it
	 */
	private long excess() {
		return bytesOfNames + BYTES_PER_LISTED_NODE * nodes.unheldCount() - budget;
	}

	private void evict() {
		long excess = excess();
		if (excess <= 0) {
			return;
		}
		Set<Long> pinnedDirectories = pins.keySet().stream().map(Key::directoryId).collect(Collectors.toSet());
		do {
			// Listed nodes go first: removing them costs only their ids, since a later page or lookup gives the entry a new node.
			// So a listing is dropped only once no directory without a pin has listed nodes left. The excess is read anew after each step, since pages that are being built list nodes meanwhile.
			if (nodes.removeUnheld(Math.ceilDiv(excess, BYTES_PER_LISTED_NODE), pinnedDirectories) == 0 && !dropLeastRecentlyUsed()) {
				return;
			}
			excess = excess();
		} while (excess > 0);
	}

	/**
	 * Drops the listing used least recently among those that are neither pinned nor the one used most recently.
	 *
	 * @return Whether there was one to drop
	 */
	private boolean dropLeastRecentlyUsed() {
		Iterator<Map.Entry<Key, Snapshot>> leastRecentlyUsedFirst = snapshots.entrySet().iterator();
		while (leastRecentlyUsedFirst.hasNext()) {
			Map.Entry<Key, Snapshot> entry = leastRecentlyUsedFirst.next();
			boolean mostRecentlyUsed = !leastRecentlyUsedFirst.hasNext();
			if (!pins.containsKey(entry.getKey()) && !mostRecentlyUsed) {
				long directoryId = entry.getKey().directoryId();
				bytesOfNames -= bytesOf(entry.getValue().names());
				leastRecentlyUsedFirst.remove();
				LOG.debug("Dropped a listing of node {}.", directoryId);
				return true;
			}
		}
		return false;
	}
}
