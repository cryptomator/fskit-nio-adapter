package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.cryptomator.frontend.fskit.protocol.Messages.Request;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * Lets tests make the backend fail or block.
 */
public class HookedOperations extends FileSystemOperations {

	@FunctionalInterface
	public interface Hook {

		void run(Path path) throws IOException;
	}

	/**
	 * @return A hook that reports being entered and then blocks until released, whether or not its thread is interrupted
	 */
	public static Hook blocking(CountDownLatch entered, CountDownLatch release) {
		return _ -> {
			entered.countDown();
			boolean interrupted = false;
			while (true) {
				try {
					release.await();
					break;
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		};
	}

	/**
	 * @return An action that blocks the first time it runs, like {@link #blocking}
	 */
	public static Runnable blockingOnce(CountDownLatch entered, CountDownLatch release) {
		AtomicBoolean blocked = new AtomicBoolean();
		Hook blocking = blocking(entered, release);
		return () -> {
			if (blocked.compareAndSet(false, true)) {
				runUnchecked(blocking, null);
			}
		};
	}

	/**
	 * The calls of a channel that {@link #hooked} runs actions before.
	 */
	public enum ChannelCall {
		READ, WRITE, FORCE, CLOSE
	}

	/**
	 * Stands in for a channel and runs the given actions before the calls they are given for.
	 */
	public static FileChannel hooked(FileChannel channel, Map<ChannelCall, Runnable> before) {
		Runnable nothing = () -> {};
		try {
			FileChannel hooked = Mockito.mock(FileChannel.class);
			Mockito.when(hooked.read(Mockito.any(ByteBuffer.class), Mockito.anyLong())).thenAnswer(invocation -> {
				before.getOrDefault(ChannelCall.READ, nothing).run();
				return channel.read(invocation.<ByteBuffer>getArgument(0), invocation.<Long>getArgument(1));
			});
			Mockito.when(hooked.write(Mockito.any(ByteBuffer.class), Mockito.anyLong())).thenAnswer(invocation -> {
				before.getOrDefault(ChannelCall.WRITE, nothing).run();
				return channel.write(invocation.<ByteBuffer>getArgument(0), invocation.<Long>getArgument(1));
			});
			Mockito.when(hooked.size()).thenAnswer(_ -> channel.size());
			Mockito.doAnswer(_ -> {
				before.getOrDefault(ChannelCall.FORCE, nothing).run();
				channel.force(false);
				return null;
			}).when(hooked).force(Mockito.anyBoolean());
			Mockito.doAnswer(_ -> {
				before.getOrDefault(ChannelCall.CLOSE, nothing).run();
				channel.close();
				return null;
			}).when(hooked).close();
			return hooked;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public volatile Hook beforeReadingAttributes = _ -> {};
	/**
	 * Runs after every read of an entry's attributes, which for a sample is before it is stamped and published.
	 */
	public volatile Hook afterReadingAttributes = _ -> {};
	/**
	 * Runs for the path of each requirement of a lock set, after the operation read the paths and before it acquires the set.
	 */
	public volatile Hook beforeLocking = _ -> {};
	/**
	 * Runs after a lookup, a create or a symlink counted a reference to the node of the given path.
	 */
	public volatile Hook afterHold = _ -> {};
	public volatile Hook beforeReadingUsableSpace = _ -> {};
	public volatile Hook beforeOpeningChannel = _ -> {};
	public volatile Hook beforeMoving = _ -> {};
	/**
	 * The options of every move, in order.
	 */
	public final List<CopyOption> moveOptions = new CopyOnWriteArrayList<>();
	public volatile Hook beforeSettingPermissions = _ -> {};
	public volatile Hook beforeSettingTimes = _ -> {};
	public volatile Hook beforeResolvingRealPath = _ -> {};
	public volatile UnaryOperator<FileChannel> channelWrapper = UnaryOperator.identity();
	public final List<FileChannel> openedChannels = new CopyOnWriteArrayList<>();
	/**
	 * Every node a lookup, a create or a symlink counted a reference to.
	 */
	public final Set<Node> heldNodes = ConcurrentHashMap.newKeySet();
	/**
	 * The failures that requests were answered with EIO for, although no status was meant for them.
	 */
	public final List<Exception> unexpectedFailures = new CopyOnWriteArrayList<>();
	/**
	 * Counted down once the session thread has closed these operations, before {@code BridgeSession.ended()} completes.
	 */
	public final CountDownLatch closed = new CountDownLatch(1);

	public HookedOperations(Path root, boolean readOnly) throws IOException {
		super(root, readOnly);
	}

	@Override
	public void close() {
		super.close();
		closed.countDown();
	}

	@Override
	void reportUnexpectedFailure(Request request, Exception e) {
		unexpectedFailures.add(e);
		super.reportUnexpectedFailure(request, e);
	}

	@Override
	PathLocks.Held lock(PathLocks.Requirement... requirements) {
		for (PathLocks.Requirement requirement : requirements) {
			runUnchecked(beforeLocking, requirement.path());
		}
		return super.lock(requirements);
	}

	@Override
	Node hold(Path storedPath, NodeType type, Node parent) {
		Node node = super.hold(storedPath, type, parent);
		heldNodes.add(node);
		runUnchecked(afterHold, storedPath);
		return node;
	}

	@Override
	BasicFileAttributes readFileAttributes(Path path) throws IOException {
		beforeReadingAttributes.run(path);
		BasicFileAttributes attributes = super.readFileAttributes(path);
		afterReadingAttributes.run(path);
		return attributes;
	}

	@Override
	long readUsableSpace() throws IOException {
		beforeReadingUsableSpace.run(null);
		return super.readUsableSpace();
	}

	@Override
	FileChannel openChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
		beforeOpeningChannel.run(path);
		FileChannel channel = channelWrapper.apply(super.openChannel(path, options, attributes));
		openedChannels.add(channel);
		return channel;
	}

	@Override
	void move(Path source, Path target, CopyOption... options) throws IOException {
		moveOptions.addAll(List.of(options));
		beforeMoving.run(source);
		super.move(source, target, options);
	}

	@Override
	void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
		beforeSettingPermissions.run(path);
		super.setPermissions(path, permissions);
	}

	@Override
	void setTimes(Path path, FileTime modified, FileTime accessed) throws IOException {
		beforeSettingTimes.run(path);
		super.setTimes(path, modified, accessed);
	}

	@Override
	Path toRealPath(Path path) throws IOException {
		beforeResolvingRealPath.run(path);
		return super.toRealPath(path);
	}

	// for seams that cannot fail, which tests only block
	private static void runUnchecked(Hook hook, Path path) {
		try {
			hook.run(path);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
