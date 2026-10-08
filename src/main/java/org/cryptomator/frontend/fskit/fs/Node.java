package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages.Attributes;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.jetbrains.annotations.Nullable;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One entry of the backing file system, as known to the kernel.
 * <p>
 * {@link NodeTable} changes {@link #path}, {@link #parentId}, {@link #type}, {@link #unlinked}, {@link #forgotten} and {@link #lookups} under its monitor. {@link #path}, {@link #parentId} and {@link #unlinked} change through a rename or a removal only under that operation's write lock on the node's path. Every operation on the node checks {@link #path} and {@link #unlinked} again once it holds its own lock set, and {@code REMOVE} and {@code RENAME} check {@link #parentId} as well. {@link #type} follows every published sample. {@link #channel} and {@link #modes} are guarded by {@link #data}, {@link #attributes} by the entry's sampling lock, or by the node's monitor once it is {@link #unlinked}.
 */
final class Node {

	final long id;
	volatile long parentId;
	/**
	 * The entry's path as the backing file system stores it. Stale once {@link #unlinked}.
	 */
	volatile Path path;
	volatile NodeType type;
	/**
	 * How many successful {@code LOOKUP}, {@code CREATE} and {@code SYMLINK} responses named this node and are not forgotten yet. Only {@code FORGET} removes a node that has any.
	 */
	long lookups;
	volatile boolean unlinked;
	/**
	 * Whether the node is gone from the table, so that its id is unknown from then on.
	 */
	volatile boolean forgotten;
	volatile @Nullable Attributes attributes;
	/**
	 * Held for reading by operations that use the channel, for writing by those that open, close or replace it or write through it.
	 */
	final ReentrantReadWriteLock data = new ReentrantReadWriteLock();
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
