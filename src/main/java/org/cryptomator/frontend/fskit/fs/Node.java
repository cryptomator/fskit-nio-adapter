package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages.Attributes;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.jetbrains.annotations.Nullable;

import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * One entry of the backing file system, as known to the kernel. Confined to the session's request thread.
 */
final class Node {

	final long id;
	long parentId;
	/**
	 * The entry's path as the backing file system stores it. Stale once {@link #unlinked}.
	 */
	Path path;
	NodeType type;
	/**
	 * Whether the kernel references this node, so that only {@code FORGET} may remove it.
	 */
	boolean held;
	boolean unlinked;
	@Nullable Attributes attributes;
	/**
	 * Set and closed through {@link NodeTable}, which keeps track of the nodes that have one.
	 */
	@Nullable FileChannel channel;
	/**
	 * The modes {@link #channel} was opened with.
	 */
	int modes;

	Node(long id, long parentId, Path path, NodeType type) {
		this.id = id;
		this.parentId = parentId;
		this.path = path;
		this.type = type;
	}
}
