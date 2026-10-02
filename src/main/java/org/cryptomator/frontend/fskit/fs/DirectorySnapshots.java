package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The directory listings that enumerations in progress page through. Confined to the session's request thread.
 */
final class DirectorySnapshots {

	private static final int MAX_SNAPSHOTS = 16;

	record Entry(String name, NodeType type) {
	}

	/**
	 * @param entries The listing, starting with "." and ".."
	 */
	record Snapshot(long verifier, long directoryId, List<Entry> entries) {
	}

	private final NodeTable nodes;
	// in access order, so that a listing an enumeration is still paging through outlives listings that were started since and left alone
	private final LinkedHashMap<Long, Snapshot> snapshotsByVerifier = new LinkedHashMap<>(MAX_SNAPSHOTS + 1, 0.75f, true);
	private long nextVerifier = 1;

	DirectorySnapshots(NodeTable nodes) {
		this.nodes = nodes;
	}

	Snapshot add(long directoryId, List<Entry> children) {
		List<Entry> entries = new ArrayList<>(children.size() + 2);
		entries.add(new Entry(".", NodeType.DIRECTORY));
		entries.add(new Entry("..", NodeType.DIRECTORY));
		entries.addAll(children);
		Snapshot snapshot = new Snapshot(nextVerifier++, directoryId, entries);
		snapshotsByVerifier.put(snapshot.verifier(), snapshot);
		if (snapshotsByVerifier.size() > MAX_SNAPSHOTS) {
			Iterator<Snapshot> leastRecentlyUsedFirst = snapshotsByVerifier.values().iterator();
			long evictedDirectoryId = leastRecentlyUsedFirst.next().directoryId();
			leastRecentlyUsedFirst.remove();
			release(Set.of(evictedDirectoryId));
		}
		return snapshot;
	}

	@Nullable Snapshot get(long directoryId, long verifier) {
		Snapshot snapshot = snapshotsByVerifier.get(verifier);
		return snapshot != null && snapshot.directoryId() == directoryId ? snapshot : null;
	}

	void invalidate(long directoryId) {
		if (snapshotsByVerifier.values().removeIf(snapshot -> snapshot.directoryId() == directoryId)) {
			release(Set.of(directoryId));
		}
	}

	void invalidateAll() {
		Set<Long> directoryIds = directoryIds();
		snapshotsByVerifier.clear();
		release(directoryIds);
	}

	// nodes that exist only because a listing reported them go with the last listing of their directory, which bounds the node table
	private void release(Set<Long> directoryIds) {
		Set<Long> stillListed = directoryIds();
		directoryIds.stream().filter(id -> !stillListed.contains(id)).forEach(nodes::removeUnheldChildren);
	}

	private Set<Long> directoryIds() {
		return snapshotsByVerifier.values().stream().map(Snapshot::directoryId).collect(Collectors.toSet());
	}
}
