package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps node ids to nodes and stored paths to the one node of that entry.
 * <p>
 * Every method holds the table's monitor only for a short section and never calls the backend under it, so a channel is closed outside it. The caller of a method that closes a channel holds that node's data lock for writing, except for {@link #clear()}, which runs once no operation is.
 */
final class NodeTable {

	private static final Logger LOG = LoggerFactory.getLogger(NodeTable.class);

	private final Map<Long, Node> nodesById = new HashMap<>();
	private final Map<Path, Node> nodesByPath = new HashMap<>();
	// FSKit sends a SYNC before every FORGET, so a sync that visited every node to find the few with a channel would make an unmount quadratic
	private final Set<Node> nodesWithChannel = new HashSet<>();
	// the nodes no lookup holds, by the id of their directory, so that they can be removed without visiting every node. The directory whose latest such node came longest ago comes first.
	private final LinkedHashMap<Long, Set<Node>> unheldChildren = new LinkedHashMap<>();
	private int unheldCount;
	private long nextId = Messages.FIRST_ASSIGNED_NODE_ID;

	NodeTable(Path root) {
		Node rootNode = new Node(Messages.ROOT_NODE_ID, Messages.PARENT_OF_ROOT_NODE_ID, root, NodeType.DIRECTORY);
		// pinned: the root is never forgotten
		rootNode.lookups = 1;
		nodesById.put(rootNode.id, rootNode);
		nodesByPath.put(rootNode.path, rootNode);
	}

	synchronized Node get(long id) throws StatusException {
		Node node = nodesById.get(id);
		if (node == null) {
			throw new StatusException(Errno.ESTALE);
		}
		return node;
	}

	synchronized @Nullable Node find(long id) {
		return nodesById.get(id);
	}

	synchronized @Nullable Node find(Path storedPath) {
		return nodesByPath.get(storedPath);
	}

	/**
	 * @return A copy of the nodes that have a channel, including those whose channel is being closed
	 */
	synchronized List<Node> withChannel() {
		return List.copyOf(nodesWithChannel);
	}

	/**
	 * @return Whether the kernel references the node. A referenced node stays in the table until the forget that drops its last reference, which closes its channel. Only a forget lowers the count, and it takes the node's data lock, so the answer holds while the caller has that lock.
	 */
	synchronized boolean isHeld(Node node) {
		return node.lookups > 0;
	}

	synchronized void setChannel(Node node, FileChannel channel, int modes) {
		node.channel = channel;
		node.modes = modes;
		nodesWithChannel.add(node);
	}

	/**
	 * Closes a node's channel. The node keeps it, and stays among the nodes with a channel, until the close has returned, so that a sync that finds it waits for the close.
	 */
	void closeChannel(Node node) throws IOException {
		FileChannel closing = node.channel;
		if (closing == null) {
			return;
		}
		try {
			closing.close();
		} finally {
			synchronized (this) {
				node.channel = null;
				node.modes = 0;
				nodesWithChannel.remove(node);
			}
		}
	}

	synchronized void setType(Node node, NodeType type) {
		node.type = type;
	}

	/**
	 * Returns the node of an entry the kernel is about to reference, and counts that reference.
	 */
	synchronized Node hold(Path storedPath, NodeType type, Node parent) {
		Node node = nodeOf(storedPath, type, parent);
		node.lookups++;
		setParent(node, parent);
		return node;
	}

	/**
	 * Returns the node of an entry that is merely reported in a listing.
	 */
	synchronized Node list(Path storedPath, NodeType type, Node parent) {
		Node node = nodeOf(storedPath, type, parent);
		setParent(node, parent);
		return node;
	}

	private Node nodeOf(Path storedPath, NodeType type, Node parent) {
		Node node = nodesByPath.computeIfAbsent(storedPath, path -> {
			Node created = new Node(nextId++, parent.id, path, type);
			nodesById.put(created.id, created);
			return created;
		});
		node.type = type;
		return node;
	}

	/**
	 * Subtracts the references the kernel dropped. A node with none left leaves the table and its channel is closed. The root always stays.
	 */
	void forget(Node node, long lookups) throws IOException {
		synchronized (this) {
			node.lookups -= lookups;
			if (node.id == Messages.ROOT_NODE_ID || node.lookups > 0 || node.forgotten) {
				return;
			}
			untrack(node);
			nodesById.remove(node.id, node);
			nodesByPath.remove(node.path, node);
			node.forgotten = true;
		}
		closeChannel(node);
	}

	/**
	 * Detaches a node whose entry no longer exists. A held node stays reachable by its id until it is forgotten.
	 */
	synchronized void unlink(Node node) {
		node.unlinked = true;
		nodesByPath.remove(node.path, node);
		if (node.lookups == 0) {
			remove(node);
		}
	}

	/**
	 * Re-paths a node and every node below it.
	 */
	synchronized void move(Node node, Path storedPath, Node parent) {
		Path oldPath = node.path;
		List<Node> moved = nodesByPath.values().stream().filter(n -> n.path.startsWith(oldPath)).toList();
		moved.forEach(n -> nodesByPath.remove(n.path));
		for (Node n : moved) {
			n.path = storedPath.resolve(oldPath.relativize(n.path));
			nodesByPath.put(n.path, n);
		}
		setParent(node, parent);
	}

	/**
	 * Removes nodes that exist only because a listing reported them, all of one directory at a time, until at least the given number is removed or only kept directories have any. Directories go in the order of their latest such node, the earliest first.
	 *
	 * @param keptDirectories The ids of the directories whose nodes stay
	 * @return The number of nodes removed
	 */
	synchronized long removeUnheld(long count, Set<Long> keptDirectories) {
		long removed = 0;
		Iterator<Map.Entry<Long, Set<Node>>> directories = unheldChildren.entrySet().iterator();
		while (removed < count && directories.hasNext()) {
			Map.Entry<Long, Set<Node>> directory = directories.next();
			if (!keptDirectories.contains(directory.getKey())) {
				Set<Node> unheld = directory.getValue();
				directories.remove();
				unheldCount -= unheld.size();
				removed += unheld.size();
				unheld.forEach(this::remove);
			}
		}
		return removed;
	}

	/**
	 * @return The number of nodes that exist only because a listing reported them
	 */
	synchronized int unheldCount() {
		return unheldCount;
	}

	/**
	 * Closes every channel and forgets every node. Called once no operation is running anymore.
	 */
	void clear() {
		List<Node> all;
		synchronized (this) {
			all = List.copyOf(nodesById.values());
		}
		for (Node node : all) {
			try {
				closeChannel(node);
			} catch (IOException | RuntimeException e) {
				FailureLog.warn(LOG, "Failed to close the channel of node " + node.id + ". Data written to it may be lost.", e);
			}
		}
		synchronized (this) {
			nodesById.values().forEach(node -> node.forgotten = true);
			nodesById.clear();
			nodesByPath.clear();
			nodesWithChannel.clear();
			unheldChildren.clear();
			unheldCount = 0;
		}
	}

	private void setParent(Node node, Node parent) {
		untrack(node);
		node.parentId = parent.id;
		// a node that left the table meanwhile, as one removed while its rename ran, stays out
		if (node.lookups == 0 && !node.forgotten) {
			Set<Node> siblings = unheldChildren.computeIfAbsent(node.parentId, _ -> new HashSet<>());
			siblings.add(node);
			unheldCount++;
			unheldChildren.putLast(node.parentId, siblings);
		}
	}

	private void untrack(Node node) {
		Set<Node> siblings = unheldChildren.get(node.parentId);
		if (siblings != null && siblings.remove(node)) {
			unheldCount--;
			if (siblings.isEmpty()) {
				unheldChildren.remove(node.parentId);
			}
		}
	}

	private void remove(Node node) {
		untrack(node);
		nodesById.remove(node.id);
		nodesByPath.remove(node.path, node);
		nodesWithChannel.remove(node);
		node.forgotten = true;
	}
}
