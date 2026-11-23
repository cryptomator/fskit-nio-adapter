package org.cryptomator.frontend.fskit.fs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Decides which nodes a {@code CHANGES} request samples.
 * <p>
 * A signal of {@link OutsideChanges} requests a sweep over every file, link and directory other than the root that the client holds, since a signal does not say what changed. A signal during a sweep requests another one after it. A node sampled while its channel was open is sampled again on its own once that channel is closed, since a vault can report the size and time of its open file until then. It does so after a change in place, and with cryptofs before 2.12.1 also after the file's ciphertext was replaced.
 * <p>
 * Its monitor is held only for short sections and never during a backend call. It is taken after a data lock and before the monitor of {@link NodeTable}.
 */
final class ChangeTracker {

	private final NodeTable nodes;
	private final Queue<Node> closedSinceSampled = new ArrayDeque<>();
	private final Set<Node> sampledWhileOpen = new HashSet<>();
	private final Queue<Node> sweep = new ArrayDeque<>();
	private boolean sweepRequested;
	private boolean closed;

	ChangeTracker(NodeTable nodes) {
		this.nodes = nodes;
	}

	synchronized void signal() {
		sweepRequested = true;
		notifyAll();
	}

	/**
	 * Waits until there are nodes to sample.
	 *
	 * @param max The most nodes to return
	 * @return The nodes to sample next
	 * @throws StatusException With {@code EIO} once this tracker is closed
	 */
	synchronized List<Node> next(int max) throws StatusException {
		boolean interrupted = false;
		try {
			while (!closed) {
				if (!closedSinceSampled.isEmpty()) {
					return drain(closedSinceSampled, max);
				} else if (!sweep.isEmpty()) {
					return drain(sweep, max);
				} else if (sweepRequested) {
					sweepRequested = false;
					sweep.addAll(nodes.heldSnapshot());
					sampledWhileOpen.removeIf(node -> node.forgotten);
				} else {
					try {
						wait();
					} catch (InterruptedException e) {
						interrupted = true;
					}
				}
			}
			throw new StatusException(Errno.EIO);
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static List<Node> drain(Queue<Node> queue, int max) {
		List<Node> next = new ArrayList<>();
		while (next.size() < max && !queue.isEmpty()) {
			next.add(queue.remove());
		}
		return next;
	}

	/**
	 * Notes that a node was sampled while it had a channel. Called under the node's data lock.
	 */
	synchronized void sampledWhileOpen(Node node) {
		sampledWhileOpen.add(node);
	}

	/**
	 * Notes that a node's channel was closed. Called under the node's data lock.
	 */
	synchronized void channelClosed(Node node) {
		if (sampledWhileOpen.remove(node)) {
			closedSinceSampled.add(node);
			notifyAll();
		}
	}

	/**
	 * Ends a wait in {@link #next(int)}, and every later one at once.
	 */
	synchronized void close() {
		closed = true;
		notifyAll();
	}

	synchronized boolean isClosed() {
		return closed;
	}
}
