package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

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
		nodes.forget(Messages.ROOT_NODE_ID);

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
		Assertions.assertFalse(listed.held);

		Node held = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		Assertions.assertSame(listed, held);
		Assertions.assertTrue(held.held);
	}

	@Test
	@DisplayName("listing a held node leaves it held")
	public void testListKeepsNodeHeld() {
		Node held = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		Assertions.assertSame(held, nodes.list(ROOT.resolve("a"), NodeType.FILE, root));
		Assertions.assertTrue(held.held);
	}

	@Test
	@DisplayName("forgetting removes a node and closes its channel")
	public void testForget(@TempDir Path tmpDir) throws IOException {
		Node node = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		node.channel = FileChannel.open(Files.createFile(tmpDir.resolve("a")), StandardOpenOption.WRITE);
		FileChannel channel = node.channel;

		nodes.forget(node.id);

		Assertions.assertFalse(channel.isOpen());
		Assertions.assertNull(nodes.find(ROOT.resolve("a")));
		Assertions.assertThrows(StatusException.class, () -> nodes.get(node.id));
	}

	@Test
	@DisplayName("a lookup after a forget gets a new id")
	public void testLookupAfterForget() throws IOException {
		Node forgotten = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		nodes.forget(forgotten.id);

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

		nodes.forget(target.id);

		Assertions.assertSame(source, nodes.find(ROOT.resolve("target")));
		Assertions.assertThrows(StatusException.class, () -> nodes.get(target.id));
	}

	@Test
	@DisplayName("forgetting an unlinked node leaves the node that took its path")
	public void testForgetUnlinkedNode() throws IOException {
		Node removed = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		nodes.unlink(removed);
		Node recreated = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);

		nodes.forget(removed.id);

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
	@DisplayName("a directory's unheld nodes go when its last snapshot is dropped")
	public void testUnheldNodesGoWithLastSnapshot() throws IOException {
		DirectorySnapshots snapshots = new DirectorySnapshots(nodes);
		Node directory = nodes.hold(ROOT.resolve("dir"), NodeType.DIRECTORY, root);
		Node other = nodes.hold(ROOT.resolve("other"), NodeType.DIRECTORY, root);
		Node held = nodes.hold(ROOT.resolve("dir/held"), NodeType.FILE, directory);
		Node listed = nodes.list(ROOT.resolve("dir/listed"), NodeType.FILE, directory);
		Node listedElsewhere = nodes.list(ROOT.resolve("other/listed"), NodeType.FILE, other);
		snapshots.add(directory.id, List.of());
		snapshots.add(directory.id, List.of());
		snapshots.add(other.id, List.of());

		// 14 more listings evict the directory's first listing, the 15th its second
		for (int i = 0; i < 14; i++) {
			snapshots.add(root.id, List.of());
		}
		Assertions.assertSame(listed, nodes.get(listed.id));
		snapshots.add(root.id, List.of());

		Assertions.assertThrows(StatusException.class, () -> nodes.get(listed.id));
		Assertions.assertNull(nodes.find(ROOT.resolve("dir/listed")));
		Assertions.assertSame(held, nodes.get(held.id));
		Assertions.assertSame(listedElsewhere, nodes.get(listedElsewhere.id));
	}

	@Test
	@DisplayName("clearing closes every channel")
	public void testClear(@TempDir Path tmpDir) throws IOException {
		Node node = nodes.hold(ROOT.resolve("a"), NodeType.FILE, root);
		node.channel = FileChannel.open(Files.createFile(tmpDir.resolve("a")), StandardOpenOption.WRITE);
		FileChannel channel = node.channel;

		nodes.clear();

		Assertions.assertFalse(channel.isOpen());
		Assertions.assertTrue(nodes.all().isEmpty());
	}

	@Test
	@DisplayName("clearing goes on after a channel that fails to close")
	public void testClearWithFailingClose() throws IOException {
		FileChannel failing = Mockito.mock(FileChannel.class);
		FileChannel other = Mockito.mock(FileChannel.class);
		Mockito.doThrow(new IOException("disk on fire")).when(failing).close();
		nodes.hold(ROOT.resolve("a"), NodeType.FILE, root).channel = failing;
		nodes.hold(ROOT.resolve("b"), NodeType.FILE, root).channel = other;
		nodes.hold(ROOT.resolve("c"), NodeType.FILE, root).channel = failing;

		nodes.clear();

		Mockito.verify(failing, Mockito.times(2)).close();
		Mockito.verify(other).close();
		Assertions.assertTrue(nodes.all().isEmpty());
	}
}
