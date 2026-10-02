package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages.Attributes;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
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

	void closeChannel() throws IOException {
		FileChannel closing = channel;
		channel = null;
		modes = 0;
		if (closing != null) {
			closing.close();
		}
	}
}
