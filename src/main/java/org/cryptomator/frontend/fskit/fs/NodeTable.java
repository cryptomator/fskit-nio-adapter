package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps node ids to nodes and stored paths to the one node of that entry. Confined to the session's request thread.
 */
final class NodeTable {

	private static final Logger LOG = LoggerFactory.getLogger(NodeTable.class);

	private final Map<Long, Node> nodesById = new HashMap<>();
	private final Map<Path, Node> nodesByPath = new HashMap<>();
	// FSKit sends a SYNC before every FORGET, so a sync that visited every node to find the few with a channel would make an unmount quadratic
	private final Set<Node> nodesWithChannel = new HashSet<>();
	private long nextId = Messages.FIRST_ASSIGNED_NODE_ID;

	NodeTable(Path root) {
		Node rootNode = new Node(Messages.ROOT_NODE_ID, Messages.PARENT_OF_ROOT_NODE_ID, root, NodeType.DIRECTORY);
		rootNode.held = true;
		nodesById.put(rootNode.id, rootNode);
		nodesByPath.put(rootNode.path, rootNode);
	}

	Node get(long id) throws StatusException {
		Node node = nodesById.get(id);
		if (node == null) {
			throw new StatusException(Errno.ESTALE);
		}
		return node;
	}

	@Nullable Node find(Path storedPath) {
		return nodesByPath.get(storedPath);
	}

	Collection<Node> withChannel() {
		return nodesWithChannel;
	}

	void setChannel(Node node, FileChannel channel, int modes) {
		node.channel = channel;
		node.modes = modes;
		nodesWithChannel.add(node);
	}

	void closeChannel(Node node) throws IOException {
		FileChannel closing = node.channel;
		nodesWithChannel.remove(node);
		node.channel = null;
		node.modes = 0;
		if (closing != null) {
			closing.close();
		}
	}

	/**
	 * Returns the node of an entry the kernel is about to reference.
	 */
	Node hold(Path storedPath, NodeType type, Node parent) {
		Node node = list(storedPath, type, parent);
		node.held = true;
		return node;
	}

	/**
	 * Returns the node of an entry that is merely reported in a listing.
	 */
	Node list(Path storedPath, NodeType type, Node parent) {
		Node node = nodesByPath.computeIfAbsent(storedPath, path -> {
			Node created = new Node(nextId++, parent.id, path, type);
			nodesById.put(created.id, created);
			return created;
		});
		node.parentId = parent.id;
		node.type = type;
		return node;
	}

	void forget(long id) throws IOException {
		Node node = nodesById.get(id);
		if (node != null && id != Messages.ROOT_NODE_ID) {
			remove(node);
			closeChannel(node);
		}
	}

	/**
	 * Detaches a node whose entry no longer exists. A held node stays reachable by its id until it is forgotten.
	 */
	void unlink(Node node) {
		node.unlinked = true;
		nodesByPath.remove(node.path, node);
		if (!node.held) {
			nodesById.remove(node.id);
			nodesWithChannel.remove(node);
		}
	}

	/**
	 * Re-paths a node and every node below it.
	 */
	void move(Node node, Path storedPath, Node parent) {
		Path oldPath = node.path;
		List<Node> moved = nodesByPath.values().stream().filter(n -> n.path.startsWith(oldPath)).toList();
		moved.forEach(n -> nodesByPath.remove(n.path));
		for (Node n : moved) {
			n.path = storedPath.resolve(oldPath.relativize(n.path));
			nodesByPath.put(n.path, n);
		}
		node.parentId = parent.id;
	}

	void removeUnheldChildren(long directoryId) {
		List<Node> unheld = nodesById.values().stream().filter(n -> !n.held && n.parentId == directoryId).toList();
		unheld.forEach(this::remove);
	}

	/**
	 * Closes every channel and forgets every node.
	 */
	void clear() {
		for (Node node : nodesById.values()) {
			try {
				closeChannel(node);
			} catch (IOException | RuntimeException e) {
				FailureLog.warn(LOG, "Failed to close the channel of node " + node.id + ". Data written to it may be lost.", e);
			}
		}
		nodesById.clear();
		nodesByPath.clear();
		nodesWithChannel.clear();
	}

	private void remove(Node node) {
		nodesById.remove(node.id);
		nodesByPath.remove(node.path, node);
		nodesWithChannel.remove(node);
	}
}
