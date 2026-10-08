package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The directory listings that enumerations in progress page through.
 * <p>
 * Every method holds one monitor, inside which it may take the node table's. A listing that a page is built from is pinned until the page is done, which keeps it from being dropped to make room for others.
 */
final class DirectorySnapshots {

	private static final int MAX_SNAPSHOTS = 16;

	/**
	 * @param type The entry's type when the listing was taken, or {@code null} if it was not read then
	 */
	record Entry(String name, @Nullable NodeType type) {
	}

	/**
	 * @param entries The listing, starting with "." and ".."
	 */
	record Snapshot(long verifier, long directoryId, List<Entry> entries) {
	}

	private final NodeTable nodes;
	// in access order, so that a listing an enumeration is still paging through outlives listings that were started since and left alone
	private final LinkedHashMap<Long, Snapshot> snapshotsByVerifier = new LinkedHashMap<>(MAX_SNAPSHOTS + 1, 0.75f, true);
	private final Map<Long, Integer> pinsByVerifier = new HashMap<>();
	private long nextVerifier = 1;

	DirectorySnapshots(NodeTable nodes) {
		this.nodes = nodes;
	}

	/**
	 * @return The new listing, pinned until it is {@link #unpin(Snapshot) unpinned}
	 */
	synchronized Snapshot add(long directoryId, List<Entry> children) {
		List<Entry> entries = new ArrayList<>(children.size() + 2);
		entries.add(new Entry(".", NodeType.DIRECTORY));
		entries.add(new Entry("..", NodeType.DIRECTORY));
		entries.addAll(children);
		Snapshot snapshot = new Snapshot(nextVerifier++, directoryId, entries);
		snapshotsByVerifier.put(snapshot.verifier(), snapshot);
		pin(snapshot);
		evict();
		return snapshot;
	}

	/**
	 * @return The listing, pinned until it is {@link #unpin(Snapshot) unpinned}, or {@code null} if there is none with that verifier for that directory
	 */
	synchronized @Nullable Snapshot get(long directoryId, long verifier) {
		Snapshot snapshot = snapshotsByVerifier.get(verifier);
		if (snapshot == null || snapshot.directoryId() != directoryId) {
			return null;
		}
		pin(snapshot);
		return snapshot;
	}

	synchronized void unpin(Snapshot snapshot) {
		pinsByVerifier.computeIfPresent(snapshot.verifier(), (_, pins) -> pins > 1 ? pins - 1 : null);
		evict();
	}

	synchronized void invalidate(long directoryId) {
		if (snapshotsByVerifier.values().removeIf(snapshot -> snapshot.directoryId() == directoryId)) {
			release(directoryId);
		}
	}

	private void pin(Snapshot snapshot) {
		pinsByVerifier.merge(snapshot.verifier(), 1, Integer::sum);
	}

	private void evict() {
		Iterator<Snapshot> leastRecentlyUsedFirst = snapshotsByVerifier.values().iterator();
		while (snapshotsByVerifier.size() > MAX_SNAPSHOTS && leastRecentlyUsedFirst.hasNext()) {
			Snapshot snapshot = leastRecentlyUsedFirst.next();
			if (!pinsByVerifier.containsKey(snapshot.verifier())) {
				leastRecentlyUsedFirst.remove();
				release(snapshot.directoryId());
			}
		}
	}

	// nodes that exist only because a listing reported them go with the last listing of their directory, which bounds the node table
	private void release(long directoryId) {
		if (snapshotsByVerifier.values().stream().noneMatch(snapshot -> snapshot.directoryId() == directoryId)) {
			nodes.removeUnheldChildren(directoryId);
		}
	}
}
