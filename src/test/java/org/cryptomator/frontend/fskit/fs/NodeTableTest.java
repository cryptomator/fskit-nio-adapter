package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class NodeTableTest {

	private static final Path ROOT = Path.of("/root");

	private NodeTable nodes;
	private Node root;

	@BeforeEach
	public void setup() throws IOException {
		nodes = new NodeTable(ROOT);
		root = nodes.get(Messages.ROOT_NODE_ID);
	}

	@Test
	@DisplayName("the root has id 2, parent 1 and cannot be forgotten")
	public void testRoot() throws IOException {
		nodes.forget(root, 1);

		Assertions.assertSame(root, nodes.get(2));
		Assertions.assertEquals(1, root.parentId);
		Assertions.assertSame(root, nodes.find(ROOT));
	}

	@Test
	@DisplayName("assigns ids from 64 upwards")
	public void testAssignedIds() {
		Assertions.assertEquals(64, nodes.hold(ROOT.resolve("a"), NodeType.FILE, root).id);
		Assertions.assertEquals(65, nodes.list(ROOT.resolve("b"), NodeType.FILE, root).id);
	}

	@Test
	@DisplayName("lookups of one path share a node")
	public void testSamePathSameNode() {
		Node first = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		Node second = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		Assertions.assertSame(first, second);
	}

	@Test
	@DisplayName("a lookup marks a listed node held")
	public void testLookupHoldsListedNode() {
		Node listed = nodes.list(ROOT.resolve("a"), NodeType.FILE, root);
		Assertions.assertEquals(0, listed.lookups);

		Node held = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		Assertions.assertSame(listed, held);
		Assertions.assertEquals(1, held.lookups);
	}

	@Test
	@DisplayName("listing a held node leaves it held")
	public void testListKeepsNodeHeld() {
		Node held = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		Assertions.assertSame(held, nodes.list(ROOT.resolve("a"), NodeType.FILE, root));
		Assertions.assertEquals(1, held.lookups);
	}

	@Test
	@DisplayName("forgetting removes a node and closes its channel")
	public void testForget(@TempDir Path tmpDir) throws IOException {
		Node node = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		FileChannel channel = FileChannel.open(Files.createFile(tmpDir.resolve("a")), StandardOpenOption.WRITE);
		nodes.setChannel(node, channel, Messages.MODE_WRITE);

		nodes.forget(node, 1);

		Assertions.assertFalse(channel.isOpen());
		Assertions.assertNull(nodes.find(ROOT.resolve("a")));
		Assertions.assertThrows(StatusException.class, () -> nodes.get(node.id));
		Assertions.assertTrue(nodes.withChannel().isEmpty());
	}

	@Test
	@DisplayName("a node goes once every lookup is forgotten, so a forget that a later lookup overtook leaves it")
	public void testLookupCounts() throws IOException {
		Node node = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		nodes.forget(node, 1);
		Assertions.assertSame(node, nodes.get(node.id));
		Assertions.assertSame(node, nodes.find(ROOT.resolve("a")));

		nodes.forget(node, 1);
		Assertions.assertThrows(StatusException.class, () -> nodes.get(node.id));
		Assertions.assertTrue(node.forgotten);
	}

	@Test
	@DisplayName("the nodes with a channel are handed out as a copy")
	public void testWithChannelIsACopy() {
		Node node = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		nodes.setChannel(node, Mockito.mock(FileChannel.class), Messages.MODE_READ);
		List<Node> withChannel = nodes.withChannel();

		nodes.setChannel(nodes.hold(ROOT.resolve("b"), NodeType.FILE, root), Mockito.mock(FileChannel.class), Messages.MODE_READ);

		Assertions.assertEquals(List.of(node), withChannel);
	}

	@Test
	@Timeout(10)
	@DisplayName("a channel close blocked in the backend keeps the node among those with a channel and blocks no other thread's use of the table")
	public void testCloseOutsideTheMonitor() throws Exception {
		Node node = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		FileChannel blocking = Mockito.mock(FileChannel.class);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Mockito.doAnswer(_ -> {
			entered.countDown();
			release.await();
			return null;
		}).when(blocking).close();
		nodes.setChannel(node, blocking, Messages.MODE_WRITE);
		try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> closing = threads.submit(() -> {
				nodes.closeChannel(node);
				return null;
			});
			Assertions.assertTrue(entered.await(5, TimeUnit.SECONDS));

			Assertions.assertSame(node, nodes.find(ROOT.resolve("a")));
			Assertions.assertNotNull(nodes.hold(ROOT.resolve("b"), NodeType.FILE, root));
			Assertions.assertEquals(List.of(node), nodes.withChannel());

			release.countDown();
			closing.get();
		}
		Assertions.assertNull(node.channel);
		Assertions.assertEquals(List.of(), nodes.withChannel());
	}

	@Test
	@DisplayName("a lookup after a forget gets a new id")
	public void testLookupAfterForget() throws IOException {
		Node forgotten = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		nodes.forget(forgotten, 1);

		Node node = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		Assertions.assertNotEquals(forgotten.id, node.id);
	}

	@Test
	@DisplayName("a move re-paths the node and its descendants")
	public void testMove() {
		Node source = nodes.hold(ROOT.resolve("source"), NodeType.DIRECTORY, root);
		Node target = nodes.hold(ROOT.resolve("target"), NodeType.DIRECTORY, root);
		Node dir = nodes.hold(ROOT.resolve("source/dir"), NodeType.DIRECTORY, source);
		Node child = nodes.hold(ROOT.resolve("source/dir/child"), NodeType.DIRECTORY, dir);
		Node grandchild = nodes.hold(ROOT.resolve("source/dir/child/grandchild"), NodeType.FILE, child);
		Node sibling = nodes.hold(ROOT.resolve("source/directory"), NodeType.FILE, source);

		nodes.move(dir, ROOT.resolve("target/moved"), target);

		Assertions.assertEquals(ROOT.resolve("target/moved"), dir.path);
		Assertions.assertEquals(target.id, dir.parentId);
		Assertions.assertEquals(ROOT.resolve("target/moved/child"), child.path);
		Assertions.assertEquals(dir.id, child.parentId);
		Assertions.assertSame(grandchild, nodes.find(ROOT.resolve("target/moved/child/grandchild")));
		Assertions.assertNull(nodes.find(ROOT.resolve("source/dir/child")));
		Assertions.assertSame(sibling, nodes.find(ROOT.resolve("source/directory")));
	}

	@Test
	@DisplayName("a node moved onto the path of an unlinked node takes that path, and the unlinked node stays reachable by id until it is forgotten")
	public void testMoveOverTarget() throws IOException {
		Node source = nodes.hold(ROOT.resolve("source"), NodeType.FILE, root);
		Node target = nodes.hold(ROOT.resolve("target"), NodeType.FILE, root);

		nodes.unlink(target);
		nodes.move(source, ROOT.resolve("target"), root);

		Assertions.assertSame(source, nodes.find(ROOT.resolve("target")));
		Assertions.assertNull(nodes.find(ROOT.resolve("source")));
		Assertions.assertSame(target, nodes.get(target.id));

		nodes.forget(target, 1);

		Assertions.assertSame(source, nodes.find(ROOT.resolve("target")));
		Assertions.assertThrows(StatusException.class, () -> nodes.get(target.id));
	}

	@Test
	@DisplayName("forgetting an unlinked node leaves the node that took its path")
	public void testForgetUnlinkedNode() throws IOException {
		Node removed = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		nodes.unlink(removed);
		Node recreated = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		nodes.forget(removed, 1);

		Assertions.assertNotSame(removed, recreated);
		Assertions.assertSame(recreated, nodes.find(ROOT.resolve("a")));
	}

	@Test
	@DisplayName("an unlinked node that is not held is removed at once")
	public void testUnlinkUnheldNode() {
		Node listed = nodes.list(ROOT.resolve("a"), NodeType.FILE, root);

		nodes.unlink(listed);

		Assertions.assertNull(nodes.find(ROOT.resolve("a")));
		Assertions.assertThrows(StatusException.class, () -> nodes.get(listed.id));
	}

	@Test
	@DisplayName("unheld nodes are removed one directory after another until enough are gone, and those of a kept directory stay")
	public void testRemoveUnheld() throws IOException {
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Node held = nodes.hold(ROOT.resolve("dir/held"), NodeType.FILE, directory);
		Node listed = nodes.list(ROOT.resolve("dir/listed"), NodeType.FILE, directory);
		Node listedToo = nodes.list(ROOT.resolve("dir/listed too"), NodeType.FILE, directory);
		Node listedElsewhere = nodes.list(ROOT.resolve("other/listed"), NodeType.FILE, other);

		nodes.removeUnheld(1, Set.of());

		Assertions.assertThrows(StatusException.class, () -> nodes.get(listed.id));
		Assertions.assertThrows(StatusException.class, () -> nodes.get(listedToo.id));
		Assertions.assertNull(nodes.find(ROOT.resolve("dir/listed")));
		Assertions.assertTrue(listed.forgotten);
		Assertions.assertSame(held, nodes.get(held.id));
		Assertions.assertSame(listedElsewhere, nodes.get(listedElsewhere.id));
		Assertions.assertEquals(1, nodes.unheldCount());

		nodes.removeUnheld(1, Set.of(other.id));
		Assertions.assertSame(listedElsewhere, nodes.get(listedElsewhere.id));

		nodes.removeUnheld(1, Set.of());
		Assertions.assertThrows(StatusException.class, () -> nodes.get(listedElsewhere.id));
		Assertions.assertEquals(0, nodes.unheldCount());
	}

	@Test
	@DisplayName("the directory that got an unheld node longest ago loses its unheld nodes first, and no further directory once enough are gone")
	public void testRemoveUnheldOrder() throws IOException {
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Node first = nodes.list(ROOT.resolve("dir/first"), NodeType.FILE, directory);
		Node second = nodes.list(ROOT.resolve("other/second"), NodeType.FILE, other);
		Node third = nodes.list(ROOT.resolve("dir/third"), NodeType.FILE, directory);

		Assertions.assertEquals(1, nodes.removeUnheld(1, Set.of()));

		Assertions.assertThrows(StatusException.class, () -> nodes.get(second.id));
		Assertions.assertSame(first, nodes.get(first.id));
		Assertions.assertSame(third, nodes.get(third.id));
	}

	@Test
	@DisplayName("a lookup in a directory leaves its place among the directories with unheld nodes")
	public void testLookupKeepsOrder() throws IOException {
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Node first = nodes.list(ROOT.resolve("dir/first"), NodeType.FILE, directory);
		Node second = nodes.list(ROOT.resolve("other/second"), NodeType.FILE, other);

		nodes.hold(ROOT.resolve("dir/looked up"), NodeType.FILE, directory);
		nodes.removeUnheld(1, Set.of());

		Assertions.assertThrows(StatusException.class, () -> nodes.get(first.id));
		Assertions.assertSame(second, nodes.get(second.id));
	}

	@Test
	@DisplayName("the unheld nodes of a directory are those listed in it that no lookup holds, whatever happened to them since")
	public void testUnheldNodesFollowChanges() throws IOException {
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Node lookedUp = nodes.list(ROOT.resolve("dir/looked up"), NodeType.FILE, directory);
		Node forgotten = nodes.list(ROOT.resolve("dir/forgotten"), NodeType.FILE, directory);
		Node unlinked = nodes.list(ROOT.resolve("dir/unlinked"), NodeType.FILE, directory);
		Node moved = nodes.list(ROOT.resolve("dir/moved"), NodeType.FILE, directory);
		Node relisted = nodes.list(ROOT.resolve("dir/relisted"), NodeType.FILE, directory);
		Node listed = nodes.list(ROOT.resolve("dir/listed"), NodeType.FILE, directory);
		Assertions.assertEquals(6, nodes.unheldCount());

		nodes.hold(lookedUp.path, NodeType.FILE, directory);
		nodes.forget(forgotten, 0);
		nodes.unlink(unlinked);
		nodes.move(moved, ROOT.resolve("other/moved"), other);
		nodes.list(relisted.path, NodeType.FILE, other);
		Assertions.assertEquals(3, nodes.unheldCount());
		nodes.removeUnheld(3, Set.of(other.id));

		Assertions.assertEquals(2, nodes.unheldCount());
		Assertions.assertThrows(StatusException.class, () -> nodes.get(listed.id));
		Assertions.assertSame(lookedUp, nodes.get(lookedUp.id));
		Assertions.assertSame(moved, nodes.get(moved.id));
		Assertions.assertSame(relisted, nodes.get(relisted.id));

		nodes.removeUnheld(2, Set.of(directory.id));

		Assertions.assertEquals(0, nodes.unheldCount());
		Assertions.assertThrows(StatusException.class, () -> nodes.get(moved.id));
		Assertions.assertThrows(StatusException.class, () -> nodes.get(relisted.id));
	}

	@Test
	@DisplayName("a move of a node that was removed meanwhile does not count it among the unheld nodes again")
	public void testMoveOfRemovedNode() {
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Node listed = nodes.list(ROOT.resolve("dir/listed"), NodeType.FILE, directory);
		nodes.removeUnheld(1, Set.of());

		nodes.move(listed, ROOT.resolve("other/listed"), other);

		Assertions.assertEquals(0, nodes.unheldCount());
		Assertions.assertNull(nodes.find(ROOT.resolve("other/listed")));
	}

	@Test
	@DisplayName("clearing closes every channel")
	public void testClear(@TempDir Path tmpDir) throws IOException {
		Node node = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		FileChannel channel = FileChannel.open(Files.createFile(tmpDir.resolve("a")), StandardOpenOption.WRITE);
		nodes.setChannel(node, channel, Messages.MODE_WRITE);

		nodes.clear();

		Assertions.assertFalse(channel.isOpen());
		Assertions.assertThrows(StatusException.class, () -> nodes.get(node.id));
		Assertions.assertTrue(nodes.withChannel().isEmpty());
	}

	@Test
	@DisplayName("clearing goes on after a channel that fails to close")
	public void testClearWithFailingClose() throws IOException {
		FileChannel failing = Mockito.mock(FileChannel.class);
		FileChannel other = Mockito.mock(FileChannel.class);
		Mockito.doThrow(new IOException("disk on fire")).when(failing).close();
		nodes.setChannel(nodes.hold(ROOT.resolve("a"), NodeType.FILE, root), failing, Messages.MODE_WRITE);
		nodes.setChannel(nodes.hold(ROOT.resolve("b"), NodeType.FILE, root), other, Messages.MODE_WRITE);
		nodes.setChannel(nodes.hold(ROOT.resolve("c"), NodeType.FILE, root), failing, Messages.MODE_WRITE);

		nodes.clear();

		Mockito.verify(failing, Mockito.times(2)).close();
		Mockito.verify(other).close();
		Assertions.assertThrows(StatusException.class, () -> nodes.get(Messages.ROOT_NODE_ID));
		Assertions.assertTrue(nodes.withChannel().isEmpty());
	}
}
