package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.fs.DirectorySnapshots.Snapshot;
import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// on a thread of its own, since an eviction that never ends does not react to an interrupt
@Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class DirectorySnapshotsTest {

	private static final Path ROOT = Path.of("/root");

	private NodeTable nodes;
	private Node root;
	// without a budget, the cache keeps only the listings that are pinned and the one used most recently
	private DirectorySnapshots snapshots;

	@BeforeEach
	public void setup() throws StatusException {
		nodes = new NodeTable(ROOT);
		root = nodes.get(Messages.ROOT_NODE_ID);
		snapshots = new DirectorySnapshots(nodes, 0);
	}

	private static List<String> names(String... children) {
		List<String> names = new ArrayList<>(List.of(".", ".."));
		names.addAll(List.of(children));
		return names;
	}

	private static Snapshot add(DirectorySnapshots snapshots, long directoryId, String... children) {
		List<String> names = names(children);
		return snapshots.add(directoryId, DirectorySnapshots.verifierOf(names), names);
	}

	@Test
	@DisplayName("a pinned listing outlasts listings added since, and is dropped for another one once it is unpinned")
	public void testPin() {
		Snapshot pinned = add(snapshots, 10, "a");
		snapshots.unpin(add(snapshots, 11, "b"));
		snapshots.unpin(add(snapshots, 12, "c"));

		Assertions.assertSame(pinned, snapshots.get(10, pinned.verifier()));
		Assertions.assertNull(snapshots.get(11, DirectorySnapshots.verifierOf(names("b"))));

		snapshots.unpin(pinned);
		snapshots.unpin(pinned);
		snapshots.unpin(add(snapshots, 13, "d"));

		Assertions.assertNull(snapshots.get(10, pinned.verifier()));
	}

	@Test
	@DisplayName("two directories with the same names have the same verifier, and a pin on the listing of one does not keep the other's")
	public void testSameNamesInTwoDirectories() {
		Snapshot pinned = add(snapshots, 10, "a");
		Snapshot other = add(snapshots, 11, "a");
		snapshots.unpin(other);

		snapshots.unpin(add(snapshots, 12, "b"));

		Assertions.assertEquals(pinned.verifier(), other.verifier());
		Assertions.assertNull(snapshots.get(11, other.verifier()));
		Assertions.assertSame(pinned, snapshots.get(10, pinned.verifier()));
	}

	@Test
	@DisplayName("a verifier differs for other names, for another order and for other boundaries between the names, and is not 0")
	public void testVerifier() {
		long verifier = DirectorySnapshots.verifierOf(names("a", "b"));

		Assertions.assertEquals(verifier, DirectorySnapshots.verifierOf(names("a", "b")));
		Assertions.assertNotEquals(verifier, DirectorySnapshots.verifierOf(names("a", "c")));
		Assertions.assertNotEquals(verifier, DirectorySnapshots.verifierOf(names("b", "a")));
		Assertions.assertNotEquals(verifier, DirectorySnapshots.verifierOf(names("ab")));
		Assertions.assertNotEquals(verifier, DirectorySnapshots.verifierOf(names("a")));
		Assertions.assertNotEquals(0, verifier);
		Assertions.assertNotEquals(0, DirectorySnapshots.verifierOf(names()));
	}

	@Test
	@DisplayName("a tag is never 0 and takes at most 31 bits, and a cookie is positive and holds the tag above the index")
	public void testTagAndCookie() {
		Assertions.assertEquals(0x7fffffffL, DirectorySnapshots.tagOf(-1));
		Assertions.assertEquals(0x40000000L, DirectorySnapshots.tagOf(Long.MIN_VALUE));
		Assertions.assertEquals(3, DirectorySnapshots.tagOf(3L << 33 | 0x1ffffffffL));
		// no bit set among those the tag takes
		Assertions.assertEquals(1, DirectorySnapshots.tagOf(0x1ffffffffL));

		Assertions.assertEquals(0x7fffffff00000007L, DirectorySnapshots.cookieOf(-1, 7));
		Assertions.assertEquals(0x1_7fffffffL, DirectorySnapshots.cookieOf(1, Integer.MAX_VALUE));
	}

	@Test
	@DisplayName("a listing is found by its tag in its own directory alone, and stays pinned until it is unpinned")
	public void testGetByTag() throws StatusException {
		Snapshot listing = add(snapshots, 10, "a");
		snapshots.unpin(listing);
		long tag = DirectorySnapshots.tagOf(listing.verifier());

		Assertions.assertNull(snapshots.getByTag(11, tag));
		Assertions.assertNull(snapshots.getByTag(10, tag ^ 2));
		Assertions.assertSame(listing, snapshots.getByTag(10, tag));

		snapshots.unpin(add(snapshots, 11, "b"));
		snapshots.unpin(add(snapshots, 12, "c"));
		Assertions.assertSame(listing, snapshots.get(10, listing.verifier()));

		snapshots.unpin(listing);
		snapshots.unpin(listing);
		snapshots.unpin(add(snapshots, 13, "d"));
		Assertions.assertNull(snapshots.get(10, listing.verifier()));
	}

	@Test
	@DisplayName("two listings of one directory with the same tag yield EIO, and neither is pinned")
	public void testGetByAmbiguousTag() {
		long verifier = 5L << 33;
		Snapshot first = snapshots.add(10, verifier, names("a"));
		Snapshot second = snapshots.add(10, verifier | 1, names("b"));

		StatusException failure = Assertions.assertThrows(StatusException.class, () -> snapshots.getByTag(10, 5));

		Assertions.assertEquals(Errno.EIO, failure.status);
		snapshots.unpin(first);
		snapshots.unpin(second);
		snapshots.unpin(add(snapshots, 11, "c"));
		snapshots.unpin(add(snapshots, 12, "d"));
		Assertions.assertNull(snapshots.get(10, verifier));
		Assertions.assertNull(snapshots.get(10, verifier | 1));
	}

	@Test
	@DisplayName("adding a listing the cache holds returns the held one, pinned once more")
	public void testAddHeldListing() {
		Snapshot held = add(snapshots, 10, "a");

		Snapshot again = add(snapshots, 10, "a");
		snapshots.unpin(held);
		snapshots.unpin(add(snapshots, 11, "b"));
		snapshots.unpin(add(snapshots, 12, "c"));

		Assertions.assertSame(held, again);
		Assertions.assertSame(held, snapshots.get(10, held.verifier()));
	}

	@Test
	@DisplayName("the listing used most recently stays although it exceeds the budget")
	public void testMostRecentlyUsedListing() {
		Snapshot only = add(snapshots, 10, "a");
		snapshots.unpin(only);

		Assertions.assertSame(only, snapshots.get(10, only.verifier()));
	}

	@Test
	@DisplayName("a dropped listing makes room for others")
	public void testDroppedListingMakesRoom() {
		// room for two listings of one entry
		DirectorySnapshots roomy = new DirectorySnapshots(nodes, 1000);
		for (long directoryId = 10; directoryId < 14; directoryId++) {
			roomy.unpin(add(roomy, directoryId, "a"));
		}
		long verifier = DirectorySnapshots.verifierOf(names("a"));

		Assertions.assertNull(roomy.get(11, verifier));
		Assertions.assertNotNull(roomy.get(12, verifier));
		Assertions.assertNotNull(roomy.get(13, verifier));
	}

	@Test
	@DisplayName("as many listings are dropped as the budget needs, and no more")
	public void testJustEnoughListingsAreDropped() {
		// room for three listings of one entry
		DirectorySnapshots roomy = new DirectorySnapshots(nodes, 1400);
		for (long directoryId = 10; directoryId < 13; directoryId++) {
			roomy.unpin(add(roomy, directoryId, "a"));
		}
		long verifier = DirectorySnapshots.verifierOf(names("a"));

		// takes the room of two of them
		add(roomy, 13, "a", "b", "c", "d", "e");

		Assertions.assertNull(roomy.get(10, verifier));
		Assertions.assertNull(roomy.get(11, verifier));
		Assertions.assertNotNull(roomy.get(12, verifier));
	}

	@Test
	@DisplayName("over the budget, the nodes that were only listed go before a listing is dropped")
	public void testListedNodesGoFirst() throws StatusException {
		// room for two listings of one entry, and not for a listed node next to them
		DirectorySnapshots roomy = new DirectorySnapshots(nodes, 1000);
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node held = nodes.hold(ROOT.resolve("dir/held"), NodeType.FILE, directory);
		Snapshot first = add(roomy, directory.id, "a");
		Node listed = nodes.list(ROOT.resolve("dir/a"), NodeType.FILE, directory);
		Snapshot second = add(roomy, root.id, "b");

		// the first listing is neither pinned nor the one used last from here on, so it could be dropped as well
		roomy.unpin(first);
		roomy.unpin(second);

		Assertions.assertThrows(StatusException.class, () -> nodes.get(listed.id));
		Assertions.assertSame(held, nodes.get(held.id));
		Assertions.assertSame(first, roomy.get(directory.id, first.verifier()));
	}

	@Test
	@DisplayName("over the budget by one listed node, only the directory whose nodes were listed longest ago loses them")
	public void testOnlyAsManyListedNodesGoAsNeeded() throws StatusException {
		// room for two listings of one entry and one listed node, and not for a second node
		DirectorySnapshots roomy = new DirectorySnapshots(nodes, 1700);
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Snapshot first = add(roomy, directory.id, "a");
		Node listed = nodes.list(ROOT.resolve("dir/a"), NodeType.FILE, directory);
		roomy.unpin(first);
		Snapshot second = add(roomy, other.id, "b");
		Node listedLater = nodes.list(ROOT.resolve("other/b"), NodeType.FILE, other);

		roomy.unpin(second);

		Assertions.assertThrows(StatusException.class, () -> nodes.get(listed.id));
		Assertions.assertSame(listedLater, nodes.get(listedLater.id));
	}

	@Test
	@DisplayName("a listed node that was moved into a directory without a listing goes before a listing is dropped")
	public void testMovedListedNode() throws StatusException {
		// room for a listing of one entry with its listed node, and not for a second listing next to them
		DirectorySnapshots roomy = new DirectorySnapshots(nodes, 1200);
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Snapshot first = add(roomy, directory.id, "a");
		Node listed = nodes.list(ROOT.resolve("dir/a"), NodeType.FILE, directory);
		roomy.unpin(first);
		nodes.move(listed, ROOT.resolve("other/a"), other);

		roomy.unpin(add(roomy, root.id));

		Assertions.assertThrows(StatusException.class, () -> nodes.get(listed.id));
		Assertions.assertSame(first, roomy.get(directory.id, first.verifier()));
	}

	@Test
	@DisplayName("the listed nodes of a directory stay while one of its listings is pinned, and go once the last one is unpinned")
	public void testPinKeepsListedNodes() throws StatusException {
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Snapshot pinned = add(snapshots, directory.id, "a");
		Snapshot pinnedToo = add(snapshots, directory.id, "a", "b");
		Node listed = nodes.list(ROOT.resolve("dir/a"), NodeType.FILE, directory);
		Snapshot unpinned = add(snapshots, other.id, "b");
		Node listedElsewhere = nodes.list(ROOT.resolve("other/b"), NodeType.FILE, other);

		snapshots.unpin(unpinned);

		Assertions.assertThrows(StatusException.class, () -> nodes.get(listedElsewhere.id));
		Assertions.assertSame(listed, nodes.get(listed.id));

		snapshots.unpin(pinned);

		Assertions.assertSame(listed, nodes.get(listed.id));

		snapshots.unpin(pinnedToo);

		Assertions.assertThrows(StatusException.class, () -> nodes.get(listed.id));
	}
}
