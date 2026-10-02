package org.cryptomator.frontend.fskit.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
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

	public volatile Hook beforeReadingAttributes = _ -> {};
	public volatile Hook beforeReadingUsableSpace = _ -> {};
	public volatile Hook beforeOpeningChannel = _ -> {};
	public volatile Hook beforeMoving = _ -> {};
	/**
	 * The options of every move, in order.
	 */
	public final List<CopyOption> moveOptions = new CopyOnWriteArrayList<>();
	public volatile Hook beforeResolvingRealPath = _ -> {};
	public volatile UnaryOperator<FileChannel> channelWrapper = UnaryOperator.identity();
	public final List<FileChannel> openedChannels = new CopyOnWriteArrayList<>();
	/**
	 * Counted down once the session's request thread has closed these operations, which is its last act.
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
	BasicFileAttributes readFileAttributes(Path path) throws IOException {
		beforeReadingAttributes.run(path);
		return super.readFileAttributes(path);
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
	Path toRealPath(Path path) throws IOException {
		beforeResolvingRealPath.run(path);
		return super.toRealPath(path);
	}
}
