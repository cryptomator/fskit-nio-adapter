package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.FrameCodec;
import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.Attributes;
import org.cryptomator.frontend.fskit.protocol.Messages.CloseRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.CloseResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.DirectoryEntry;
import org.cryptomator.frontend.fskit.protocol.Messages.Failure;
import org.cryptomator.frontend.fskit.protocol.Messages.ForgetRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ForgetResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.FreeSpace;
import org.cryptomator.frontend.fskit.protocol.Messages.GetattrRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.GetattrResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.HelloRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.LookupRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.LookupResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.NodeType;
import org.cryptomator.frontend.fskit.protocol.Messages.OpenRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.OpenResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.ReadRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ReadResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.ReaddirRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ReaddirResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.ReadlinkRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ReadlinkResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.RemoveRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.RemoveResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.RenameRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.RenameResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.Request;
import org.cryptomator.frontend.fskit.protocol.Messages.Response;
import org.cryptomator.frontend.fskit.protocol.Messages.SetattrRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.SetattrResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.StatfsRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.StatfsResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.SymlinkRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.SymlinkResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.SyncRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.SyncResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.Timestamp;
import org.cryptomator.frontend.fskit.protocol.Messages.WriteRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.WriteResponse;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.cryptomator.frontend.fskit.fs.PathLocks.Requirement.readEntries;
import static org.cryptomator.frontend.fskit.fs.PathLocks.Requirement.readPath;
import static org.cryptomator.frontend.fskit.fs.PathLocks.Requirement.writeEntries;
import static org.cryptomator.frontend.fskit.fs.PathLocks.Requirement.writePath;

/**
 * Serves the operations of one mounted volume against a root {@link Path}.
 * <p>
 * Requests run concurrently. Each operation takes the lock set of {@link PathLocks} that its entries need, then the data locks of its nodes, in ascending node id where it needs several. An operation reads its nodes' paths before it takes the set. Once it holds the set, it checks that they are unchanged, and otherwise starts again. An entry's attributes are read, stamped with a generation and published under its sampling lock, and every operation that changes them samples them again afterwards, so that a higher generation always means a later state.
 * <p>
 * Lock order, outermost first: the lock set, data locks, a sampling lock or the monitor of an unlinked node, the monitor of {@link DirectorySnapshots}, the monitor of {@link NodeTable}.
 */
@SuppressWarnings("OctalInteger")
public class FileSystemOperations implements Closeable {

	private static final Logger LOG = LoggerFactory.getLogger(FileSystemOperations.class);
	private static final int DEFAULT_FILE_MODE = 0644;
	private static final int DEFAULT_DIRECTORY_MODE = 0755;
	private static final int FIRST_CHILD_INDEX = 2; // listings start with "." and ".."
	private static final int MAX_LINK_TARGET_BYTES = 1023; // macOS cuts a longer target off without an error

	private final boolean readOnly;
	private final FileStore fileStore;
	private final boolean posix;
	private final NodeTable nodes;
	private final DirectorySnapshots snapshots;
	private final PathLocks locks;
	private final EventLogOptOut eventLogOptOut;
	// 0 is left to records that never change and to an unknown usable space
	private final AtomicLong generations = new AtomicLong();

	public FileSystemOperations(Path root, boolean readOnly) throws IOException {
		this.readOnly = readOnly;
		this.fileStore = Files.getFileStore(root);
		this.posix = fileStore.supportsFileAttributeView(PosixFileAttributeView.class);
		this.nodes = new NodeTable(root);
		this.snapshots = new DirectorySnapshots(nodes);
		this.locks = new PathLocks(root);
		this.eventLogOptOut = new EventLogOptOut(now());
	}

	public Response handle(Request request) {
		try {
			Response answer = eventLogOptOut.handle(request);
			if (answer != null) {
				return answer;
			}
			return switch (request) {
				case HelloRequest _ -> throw new StatusException(Errno.EINVAL);
				case StatfsRequest _ -> statfs();
				case LookupRequest r -> lookup(r);
				case ForgetRequest r -> forget(r);
				case GetattrRequest r -> getattr(r);
				case SetattrRequest r -> setattr(r);
				case ReaddirRequest r -> readdir(r);
				case CreateRequest r -> create(r);
				case RemoveRequest r -> remove(r);
				case RenameRequest r -> rename(r);
				case OpenRequest r -> open(r);
				case CloseRequest r -> close(r);
				case ReadRequest r -> read(r);
				case WriteRequest r -> write(r);
				case SyncRequest _ -> sync();
				case ReadlinkRequest r -> readlink(r);
				case SymlinkRequest r -> symlink(r);
			};
		} catch (IOException | RuntimeException e) {
			int status = Errno.of(e);
			if (status == Errno.EIO && !(e instanceof StatusException)) {
				reportUnexpectedFailure(request, e);
			}
			return new Failure(status);
		}
	}

	/**
	 * Closes every open channel and forgets every node. Called once no request is running anymore.
	 */
	@Override
	public void close() {
		nodes.clear();
	}

	/* operations */

	private StatfsResponse statfs() throws IOException {
		return new StatfsResponse(fileStore.getTotalSpace(), sampleFreeSpace());
	}

	private LookupResponse lookup(LookupRequest request) throws IOException {
		// ahead of every check of the parent, since the extension answers the same without asking
		if (HiddenNames.contains(request.name())) {
			throw new StatusException(Errno.ENOENT);
		}
		return readingDirectory(request.parentId(), directory -> {
			// resolved before the sampling lock, since it may list the whole directory
			Child child = findChild(directory, request.name());
			if (child == null || !child.sameName()) {
				throw new NoSuchFileException(request.name());
			}
			try (var _ = locks.sample(child.path())) {
				BasicFileAttributes attributes = readFileAttributes(child.path());
				// counts the lookup, which keeps a FORGET from removing the node before the reply. Nothing from here on can fail.
				Node node = hold(child.path(), typeOf(attributes), directory);
				return new LookupResponse(publish(node, attributes), name(node));
			}
		});
	}

	private ForgetResponse forget(ForgetRequest request) throws IOException {
		Node node = nodes.find(request.nodeId());
		if (node != null) {
			try (var _ = lockDataForWriting(node)) {
				nodes.forget(node, request.lookups());
			}
		}
		return new ForgetResponse(freeSpace());
	}

	private GetattrResponse getattr(GetattrRequest request) throws IOException {
		return new GetattrResponse(onNode(request.nodeId(), false, this::refresh));
	}

	private SetattrResponse setattr(SetattrRequest request) throws IOException {
		assertWritable();
		return onNode(request.nodeId(), true, node -> {
			if ((request.valid() & Messages.ATTRIBUTE_SIZE) != 0 && request.size() < 0) {
				throw new StatusException(Errno.EINVAL);
			}
			int times = request.valid() & (Messages.ATTRIBUTE_ACCESSED | Messages.ATTRIBUTE_MODIFIED);
			// converted before anything is applied, so that a time the conversion rejects fails the request with nothing changed
			FileTime modified = (times & Messages.ATTRIBUTE_MODIFIED) != 0 ? fileTime(request.modified()) : null;
			FileTime accessed = (times & Messages.ATTRIBUTE_ACCESSED) != 0 ? fileTime(request.accessed()) : null;
			boolean modeRequested = (request.valid() & Messages.ATTRIBUTE_MODE) != 0 && posix;
			if (modeRequested) {
				// setting permissions follows a link, and the entry may have become one since its type was last read
				refresh(node);
			}
			// permissions and times are set by path, which a removed item no longer has: a file created under its old name since must stay untouched
			boolean settableByPath = !node.unlinked && node.type != NodeType.SYMLINK;
			boolean setsMode = modeRequested && settableByPath;
			boolean setsTimes = times != 0 && settableByPath;
			// the backing file system may need the owner's read permission to set times, so they go ahead of a mode without it and after a mode with it
			boolean timesFirst = setsMode && (request.mode() & 0400) == 0;
			int applied = 0;
			try {
				if ((request.valid() & Messages.ATTRIBUTE_SIZE) != 0 && node.type == NodeType.FILE) {
					truncateOrExpand(node, request.size());
					applied |= Messages.ATTRIBUTE_SIZE;
				}
				if (setsTimes && timesFirst) {
					setTimes(node.path, modified, accessed);
					applied |= times;
				}
				if (setsMode) {
					setPermissions(node.path, FileAttributesUtil.octalModeToPosixPermissions(request.mode()));
					applied |= Messages.ATTRIBUTE_MODE;
				}
				if (setsTimes && !timesFirst) {
					setTimes(node.path, modified, accessed);
					applied |= times;
				}
			} catch (IOException | RuntimeException e) {
				if (applied == 0) {
					throw e;
				}
				FailureLog.warn(LOG, "Unable to apply every attribute requested for node " + node.id + ". Replying with the ones that took effect.", e);
			}
			int established = applied;
			Attributes attributes = refreshAfterChange(node, last -> new Attributes(last.type(), //
					(established & Messages.ATTRIBUTE_MODE) != 0 ? request.mode() : last.mode(), //
					(established & Messages.ATTRIBUTE_SIZE) != 0 ? request.size() : last.size(), //
					last.nodeId(), last.parentId(), //
					(established & Messages.ATTRIBUTE_MODIFIED) != 0 ? request.modified() : last.modified(), //
					(established & Messages.ATTRIBUTE_ACCESSED) != 0 ? request.accessed() : last.accessed(), //
					last.created(), last.generation()));
			return new SetattrResponse(applied, attributes, freeSpace());
		});
	}

	private ReaddirResponse readdir(ReaddirRequest request) throws IOException {
		return readingDirectory(request.nodeId(), directory -> {
			// A listing that starts with attributes gets each entry's type with the attributes its pages read, so only one that starts without them reads the types with the listing.
			DirectorySnapshots.Snapshot snapshot = request.cookie() == 0 ? snapshots.add(directory.id, list(directory, !request.wantAttributes())) : snapshots.get(directory.id, request.verifier());
			if (snapshot == null) {
				throw new StatusException(Messages.STATUS_INVALID_COOKIE);
			}
			try {
				return page(directory, snapshot, request);
			} finally {
				snapshots.unpin(snapshot);
			}
		});
	}

	private ReaddirResponse page(Node directory, DirectorySnapshots.Snapshot snapshot, ReaddirRequest request) throws IOException {
		int index = firstIndex(request, snapshot.entries().size());
		List<DirectoryEntry> page = new ArrayList<>();
		int capacity = ReaddirResponse.ENTRIES_CAPACITY;
		for (; index < snapshot.entries().size(); index++) {
			DirectorySnapshots.Entry entry = snapshot.entries().get(index);
			capacity -= DirectoryEntry.maxEncodedLength(entry.name());
			if (capacity < 0) {
				break;
			}
			DirectoryEntry resolved = resolve(directory, entry, index, request.wantAttributes());
			if (resolved != null) {
				page.add(resolved);
			}
		}
		return new ReaddirResponse(snapshot.verifier(), index < snapshot.entries().size(), page);
	}

	private CreateResponse create(CreateRequest request) throws IOException {
		assertWritable();
		return addingEntry(request.parentId(), request.name(), (directory, target) -> create(request, directory, target));
	}

	private CreateResponse create(CreateRequest request, Node directory, Path target) throws IOException {
		Set<PosixFilePermission> permissions = FileAttributesUtil.octalModeToPosixPermissions(request.mode());
		FileAttribute<?>[] initialPermissions = posix ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(permissions)} : new FileAttribute<?>[0];
		FileChannel channel = null;
		switch (request.type()) {
			// whoever creates a file may write to it whatever its mode, which takes a channel opened while creating it.
			// NOFOLLOW_LINKS: cryptofs would otherwise create the target of a link that has the name, although the file has to be new
			case FILE -> channel = openChannel(target, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), initialPermissions);
			case DIRECTORY -> Files.createDirectory(target, initialPermissions);
			case SYMLINK -> throw new StatusException(Errno.ENOTSUP);
		}
		if (posix) {
			applyExactly(target, permissions);
		}
		return added(target, request.type(), request.mode(), 0, directory, channel, CreateResponse::new);
	}

	@FunctionalInterface
	private interface AddedReply<R> {

		R of(Attributes attributes, String name, Attributes directoryAttributes, FreeSpace freeSpace);
	}

	/**
	 * Gives an entry that was just created in a directory its node and builds the reply. The type, mode and size are what the entry was created with, which the reply reports if its attributes cannot be read.
	 *
	 * @param channel The channel the entry was created with, or {@code null}
	 */
	private <R> R added(Path entry, NodeType type, int mode, long size, Node directory, @Nullable FileChannel channel, AddedReply<R> reply) {
		snapshots.invalidate(directory.id);
		Node node = hold(storedPathOrElse(entry), type, directory);
		try (var _ = lockDataForWriting(node)) {
			closeLeftoverChannel(node);
			if (channel != null) {
				nodes.setChannel(node, channel, Messages.MODE_READ | Messages.MODE_WRITE);
			}
			Timestamp now = now();
			Attributes attributes = refreshAfterChange(node, _ -> new Attributes(type, mode, size, node.id, node.parentId, now, now, now, 0));
			return reply.of(attributes, name(node), refreshAfterChange(directory, UnaryOperator.identity()), freeSpace());
		}
	}

	// the mode an entry is created with is cut down by this process's umask, although the kernel has applied the caller's already
	private void applyExactly(Path created, Set<PosixFilePermission> permissions) {
		try {
			setPermissions(created, permissions);
		} catch (IOException | RuntimeException e) {
			FailureLog.warn(LOG, "Unable to set the permissions of a created entry.", e);
		}
	}

	// a node still mapped at the path of a new entry stands for one that was deleted outside the volume, and its channel would otherwise be dropped unclosed
	private void closeLeftoverChannel(Node node) {
		try {
			nodes.closeChannel(node);
		} catch (IOException | RuntimeException e) {
			FailureLog.warn(LOG, "Unable to close the channel of an entry that was deleted outside the volume.", e);
		}
	}

	private RemoveResponse remove(RemoveRequest request) throws IOException {
		assertWritable();
		while (true) {
			Node node = linked(request.nodeId());
			Node directory = linkedDirectory(request.parentId());
			Path path = node.path;
			Path directoryPath = directory.path;
			try (var _ = lock(readPath(directoryPath), writeEntries(directoryPath), writePath(path))) {
				if (!at(node, path) || !at(directory, directoryPath)) {
					continue;
				}
				if (!named(node, directory, request.name())) {
					throw new StatusException(Errno.ENOENT);
				}
				try (var _ = lockData(node, true)) {
					Attributes attributes = refresh(node);
					try {
						Files.delete(node.path);
					} catch (DirectoryNotEmptyException e) {
						deleteHiddenEntries(node.path);
						Files.delete(node.path);
					}
					nodes.unlink(node);
					snapshots.invalidate(directory.id);
					return new RemoveResponse(attributes, refreshAfterChange(directory, UnaryOperator.identity()), freeSpace());
				}
			}
		}
	}

	private RenameResponse rename(RenameRequest request) throws IOException {
		assertWritable();
		while (true) {
			Node node = linked(request.nodeId());
			Node sourceDirectory = linkedDirectory(request.sourceParentId());
			Node destinationDirectory = linkedDirectory(request.destinationParentId());
			checkNewName(request.destinationName());
			Path path = node.path;
			Path sourcePath = sourceDirectory.path;
			Path destinationPath = destinationDirectory.path;
			Path target = destinationPath.resolve(compose(request.destinationName()));
			try (var _ = lock(readPath(sourcePath), writeEntries(sourcePath), readPath(destinationPath), writeEntries(destinationPath), writePath(path), writePath(target))) {
				if (!at(node, path) || !at(sourceDirectory, sourcePath) || !at(destinationDirectory, destinationPath)) {
					continue;
				}
				if (!named(node, sourceDirectory, request.sourceName())) {
					throw new StatusException(Errno.ENOENT);
				}
				return rename(request, node, sourceDirectory, destinationDirectory);
			}
		}
	}

	private RenameResponse rename(RenameRequest request, Node node, Node sourceDirectory, Node destinationDirectory) throws IOException {
		if (node.type == NodeType.DIRECTORY && destinationDirectory.path.startsWith(node.path)) {
			// A directory cannot move into itself or below itself. The kernel forwards such a request, and cryptofs carries it out, which detaches the directory and everything in it.
			throw new StatusException(Errno.EINVAL);
		}
		Child existing = findChild(destinationDirectory, request.destinationName());
		// the entry this rename would replace. The renamed entry itself, found under another spelling, does not count.
		Child other = existing != null && !existing.path().equals(node.path) ? existing : null;
		if (other != null && !other.sameName()) {
			// a case variant on a case-insensitive store: this volume reports it as absent, so the move must not replace it
			throw new StatusException(Errno.EEXIST);
		}
		Node replaced = other != null ? nodes.find(other.path()) : null;
		try (var _ = lockDataForWriting(node, replaced)) {
			assertNotForgotten(node);
			// read just before the change, which decides how to move and is what the reply reports of the replaced item. A store whose real paths are not checked for existence, as cryptofs, may have named an entry that is not there.
			BasicFileAttributes otherAttributes = other != null ? sampleBeforeReplacing(other.path(), replaced) : null;
			if (otherAttributes == null) {
				replaced = null;
			}
			Path target = existing != null && existing.sameName() ? existing.path() : destinationDirectory.path.resolve(compose(request.destinationName()));
			try {
				// A move that is not atomic may copy the source and delete it, which leaves an open channel on the deleted file.
				// It also deletes the target before anything has taken its place.
				// Where the atomic move of the first two cases is impossible, as across file stores below the root, the reply is EXDEV and the caller copies, as it does between volumes.
				if (otherAttributes == null) {
					move(node.path, target, StandardCopyOption.ATOMIC_MOVE);
				} else if (otherAttributes.isRegularFile() && node.type == NodeType.FILE) {
					move(node.path, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} else {
					// cryptofs cannot replace a directory or a link in one step
					try {
						move(node.path, target, StandardCopyOption.REPLACE_EXISTING);
					} catch (DirectoryNotEmptyException e) {
						if (node.type != NodeType.DIRECTORY) {
							throw e;
						}
						// a directory that holds only hidden files looks empty, and an empty one may be replaced
						deleteHiddenEntries(target);
						move(node.path, target, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			} catch (FileAlreadyExistsException e) {
				// the backing file system refuses to replace the target although asked to, as cryptofs does for a file that is open
				throw new StatusException(Errno.EBUSY);
			}
			if (replaced != null) {
				nodes.unlink(replaced);
			}
			// the backing file system decides which spelling it stores, so the name has to be read back
			nodes.move(node, storedPathOrElse(target), destinationDirectory);
			// listings of the moved directory and below it hold names, which stay valid, and are resolved against the directory's path when they are served
			snapshots.invalidate(sourceDirectory.id);
			snapshots.invalidate(destinationDirectory.id);
			Attributes attributes = refreshAfterChange(node, last -> new Attributes(last.type(), last.mode(), last.size(), last.nodeId(), destinationDirectory.id, last.modified(), last.accessed(), last.created(), last.generation()));
			return new RenameResponse(name(node), attributes, refreshAfterChange(sourceDirectory, UnaryOperator.identity()), refreshAfterChange(destinationDirectory, UnaryOperator.identity()), replaced != null ? replaced.attributes : null, freeSpace());
		}
	}

	/**
	 * @return The attributes of the entry a rename is about to replace, or {@code null} if there is none
	 */
	private @Nullable BasicFileAttributes sampleBeforeReplacing(Path path, @Nullable Node replaced) throws IOException {
		try (var _ = locks.sample(path)) {
			BasicFileAttributes attributes = readFileAttributes(path);
			if (replaced != null) {
				publish(replaced, attributes);
			}
			return attributes;
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	private OpenResponse open(OpenRequest request) throws IOException {
		return onNode(request.nodeId(), true, node -> {
			// macOS opens a link itself to copy it. Like a directory, a link gets no channel, so nothing is followed.
			if (node.type == NodeType.FILE) {
				ensureOpen(node, request.modes() & (Messages.MODE_READ | Messages.MODE_WRITE));
			}
			return new OpenResponse();
		});
	}

	private CloseResponse close(CloseRequest request) throws IOException {
		return onNode(request.nodeId(), true, node -> {
			if ((request.keptModes() & (Messages.MODE_READ | Messages.MODE_WRITE)) == 0) {
				nodes.closeChannel(node);
			}
			return new CloseResponse(freeSpace());
		});
	}

	private ReadResponse read(ReadRequest request) throws IOException {
		return underPathLock(request.nodeId(), node -> {
			if (request.offset() < 0 || request.length() < 0 || request.length() > FrameCodec.MAX_PAYLOAD_LENGTH) {
				throw new StatusException(Errno.EINVAL);
			}
			Lock held = node.data.readLock();
			held.lock();
			try {
				assertNotForgotten(node);
				if (needsOpening(node, Messages.MODE_READ)) {
					// a channel is swapped only under the write lock, which cannot be had while holding the read lock
					held.unlock();
					held = node.data.writeLock();
					held.lock();
					assertNotForgotten(node);
				}
				FileChannel channel = ensureOpen(node, Messages.MODE_READ);
				ByteBuffer data = ByteBuffer.allocate(request.length());
				while (data.hasRemaining() && channel.read(data, request.offset() + data.position()) >= 0) {
					// read until the buffer is full or the file ends
				}
				// after the bytes, since reading may have changed the access time
				return new ReadResponse(refresh(node), data.flip());
			} finally {
				held.unlock();
			}
		});
	}

	private WriteResponse write(WriteRequest request) throws IOException {
		assertWritable();
		return onNode(request.nodeId(), true, node -> {
			if (request.offset() < 0) {
				throw new StatusException(Errno.EINVAL);
			}
			FileChannel channel = ensureOpen(node, Messages.MODE_WRITE);
			ByteBuffer data = request.data().duplicate();
			int length = data.remaining();
			while (data.hasRemaining()) {
				channel.write(data, request.offset() + data.position() - request.data().position());
			}
			long end = request.offset() + length;
			Attributes attributes = refreshAfterChange(node, last -> new Attributes(last.type(), last.mode(), Math.max(last.size(), end), last.nodeId(), last.parentId(), now(), last.accessed(), last.created(), last.generation()));
			return new WriteResponse(length, attributes, freeSpace());
		});
	}

	private SyncResponse sync() throws IOException {
		IOException firstFailure = null;
		for (Node node : nodes.withChannel()) {
			Lock lock = node.data.readLock();
			// waits for a close in progress; the closed channel is not forced
			lock.lock();
			try {
				FileChannel channel = node.channel;
				if (channel != null) {
					channel.force(false);
				}
			} catch (IOException e) {
				firstFailure = firstFailure != null ? firstFailure : e;
			} finally {
				lock.unlock();
			}
		}
		if (firstFailure != null) {
			throw firstFailure;
		}
		return new SyncResponse(freeSpace());
	}

	private ReadlinkResponse readlink(ReadlinkRequest request) throws IOException {
		return onNode(request.nodeId(), false, node -> {
			if (node.unlinked) {
				throw new StatusException(Errno.ESTALE);
			}
			Attributes attributes = refresh(node);
			if (node.type != NodeType.SYMLINK) {
				throw new StatusException(Errno.EINVAL);
			}
			String target = Files.readSymbolicLink(node.path).toString();
			checkLinkTarget(target);
			return new ReadlinkResponse(attributes, target);
		});
	}

	private SymlinkResponse symlink(SymlinkRequest request) throws IOException {
		assertWritable();
		return addingEntry(request.parentId(), request.name(), (directory, link) -> symlink(request, directory, link));
	}

	private SymlinkResponse symlink(SymlinkRequest request, Node directory, Path link) throws IOException {
		Path target = link.getFileSystem().getPath(request.target());
		// checked as the backing file system spells it, since a vault's normalization can lengthen a target
		String stored = target.toString();
		checkLinkTarget(stored);
		Files.createSymbolicLink(link, target);
		return added(link, NodeType.SYMLINK, DEFAULT_FILE_MODE, utf8Length(stored), directory, null, SymlinkResponse::new);
	}

	private static void checkLinkTarget(String target) throws StatusException {
		if (utf8Length(target) > MAX_LINK_TARGET_BYTES) {
			throw new StatusException(Errno.ENAMETOOLONG);
		}
	}

	private static int utf8Length(String string) {
		return string.getBytes(StandardCharsets.UTF_8).length;
	}

	/* nodes */

	/**
	 * Returns a node whose entry still exists. A removed item lives on only through its open channel, so nothing that works by path may reach it.
	 */
	private Node linked(long id) throws StatusException {
		Node node = nodes.get(id);
		if (node.unlinked) {
			throw new StatusException(Errno.ESTALE);
		}
		return node;
	}

	private Node linkedDirectory(long id) throws StatusException {
		Node node = linked(id);
		if (node.type != NodeType.DIRECTORY) {
			throw new StatusException(Errno.ENOTDIR);
		}
		return node;
	}

	private void assertWritable() throws StatusException {
		if (readOnly) {
			throw new StatusException(Errno.EROFS);
		}
	}

	private static String name(Node node) {
		return node.path.getFileName().toString();
	}

	/**
	 * @return Whether the node still has the parent and the name a request names it by, which another rename may have changed meanwhile
	 */
	private static boolean named(Node node, Node directory, String name) {
		return node.parentId == directory.id && compose(name(node)).equals(compose(name));
	}

	/* locks */

	@FunctionalInterface
	private interface NodeOperation<T> {

		T run(Node node) throws IOException;
	}

	/**
	 * @return The path a lock set for the node is built from, or {@code null} for a node that is unlinked and takes no path lock
	 */
	private static @Nullable Path pathOf(Node node) {
		return node.unlinked ? null : node.path;
	}

	/**
	 * @return Whether the node still has the path a lock set was built from
	 */
	private static boolean at(Node node, @Nullable Path path) {
		return Objects.equals(pathOf(node), path);
	}

	/**
	 * Runs an operation on the names in a directory under the read locks of its path and its entries.
	 */
	private <T> T readingDirectory(long id, NodeOperation<T> operation) throws IOException {
		while (true) {
			Node directory = linkedDirectory(id);
			Path path = directory.path;
			try (var _ = lock(readPath(path), readEntries(path))) {
				if (at(directory, path)) {
					return operation.run(directory);
				}
			}
		}
	}

	@FunctionalInterface
	private interface EntryOperation<T> {

		T run(Node directory, Path entry) throws IOException;
	}

	/**
	 * Runs an operation that adds an entry to a directory under the read lock of the directory's path, the write lock of its entries and the write lock of the new entry's path.
	 */
	private <T> T addingEntry(long directoryId, String name, EntryOperation<T> operation) throws IOException {
		while (true) {
			Node directory = linkedDirectory(directoryId);
			checkNewName(name);
			Path path = directory.path;
			Path entry = path.resolve(compose(name));
			try (var _ = lock(readPath(path), writeEntries(path), writePath(entry))) {
				if (at(directory, path)) {
					return operation.run(directory, entry);
				}
			}
		}
	}

	/**
	 * Runs an operation on one node under the read lock of its path, and under its data lock.
	 */
	private <T> T onNode(long id, boolean exclusiveData, NodeOperation<T> operation) throws IOException {
		return underPathLock(id, node -> {
			try (var _ = lockData(node, exclusiveData)) {
				return operation.run(node);
			}
		});
	}

	/**
	 * Runs an operation on one node under the read lock of its path, which a node that is unlinked does without.
	 */
	private <T> T underPathLock(long id, NodeOperation<T> operation) throws IOException {
		while (true) {
			Node node = nodes.get(id);
			Path path = pathOf(node);
			try (var _ = path != null ? lock(readPath(path)) : PathLocks.Held.NONE) {
				if (at(node, path)) {
					return operation.run(node);
				}
			}
		}
	}

	private static PathLocks.Held lockData(Node node, boolean exclusive) throws StatusException {
		Lock lock = exclusive ? node.data.writeLock() : node.data.readLock();
		lock.lock();
		try {
			assertNotForgotten(node);
		} catch (StatusException e) {
			lock.unlock();
			throw e;
		}
		return lock::unlock;
	}

	/**
	 * Takes the data locks of the given nodes for writing, in ascending node id. Leaves out {@code null}.
	 */
	private static PathLocks.Held lockDataForWriting(@Nullable Node... nodes) {
		List<Lock> ordered = Stream.of(nodes).filter(Objects::nonNull).sorted(Comparator.comparingLong(node -> node.id)).map(node -> (Lock) node.data.writeLock()).toList();
		ordered.forEach(Lock::lock);
		return () -> ordered.reversed().forEach(Lock::unlock);
	}

	private static void assertNotForgotten(Node node) throws StatusException {
		if (node.forgotten) {
			throw new StatusException(Errno.ESTALE);
		}
	}

	/* names */

	/**
	 * @param path     The entry's path as the backing file system stores it
	 * @param sameName Whether the stored name is the requested one, disregarding Unicode normalization. If not, they differ in case and the store is case-insensitive.
	 */
	private record Child(Path path, boolean sameName) {
	}

	private @Nullable Child findChild(Node directory, String name) throws IOException {
		checkName(name);
		String composed = compose(name);
		for (String spelling : composed.equals(name) ? List.of(name) : List.of(name, composed)) {
			try {
				Path stored = storedPath(directory.path.resolve(spelling));
				return new Child(stored, compose(stored.getFileName().toString()).equals(composed));
			} catch (NoSuchFileException e) {
				// try the next spelling
			}
		}
		return null;
	}

	private static void checkName(String name) throws StatusException {
		if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0) {
			throw new StatusException(Errno.EINVAL);
		}
	}

	private static void checkNewName(String name) throws StatusException {
		checkName(name);
		if (HiddenNames.contains(name)) {
			throw new StatusException(Errno.EPERM);
		}
	}

	private static String compose(String name) {
		return Normalizer.normalize(name, Normalizer.Form.NFC);
	}

	/**
	 * Returns the path with its last name in the case and Unicode form the backing file system stores, which toRealPath with NOFOLLOW_LINKS reports.
	 *
	 * @throws NoSuchFileException If there is no such entry. A store that does not check, as cryptofs, returns the path in its normal form instead.
	 */
	private Path storedPath(Path path) throws IOException {
		try {
			return path.resolveSibling(toRealPath(path).getFileName());
		} catch (AccessDeniedException e) {
			// on macOS toRealPath reads every ancestor directory, which fails below one that may be searched but not read, as .Trashes is
			return storedPathAccordingToParent(path);
		}
	}

	private Path storedPathAccordingToParent(Path path) throws IOException {
		String name = path.getFileName().toString();
		Path differentSpelling = null;
		try (DirectoryStream<Path> siblings = Files.newDirectoryStream(path.getParent())) {
			for (Path sibling : siblings) {
				String siblingName = sibling.getFileName().toString();
				if (siblingName.equals(name)) {
					return path;
				} else if (compose(siblingName).equalsIgnoreCase(compose(name))) {
					differentSpelling = sibling;
				}
			}
		} catch (AccessDeniedException e) {
			// nothing can tell the stored spelling of an entry in a directory that cannot be read, but whether it exists can be told
			readFileAttributes(path);
			return path;
		} catch (DirectoryIteratorException e) {
			throw e.getCause();
		}
		if (differentSpelling == null) {
			throw new NoSuchFileException(path.toString());
		}
		return path.resolveSibling(differentSpelling.getFileName());
	}

	private Path storedPathOrElse(Path path) {
		try {
			return storedPath(path);
		} catch (IOException | RuntimeException e) {
			FailureLog.warn(LOG, "Unable to determine the stored name of a changed entry. Assuming the requested name.", e);
			return path;
		}
	}

	/* listings */

	/**
	 * @param withTypes Whether to read every entry's type, which takes a read of its attributes
	 */
	private List<DirectorySnapshots.Entry> list(Node directory, boolean withTypes) throws IOException {
		List<DirectorySnapshots.Entry> entries = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory.path)) {
			for (Path child : stream) {
				String name = child.getFileName().toString();
				if (EventLogOptOut.takes(directory.id, name) || HiddenNames.contains(name)) {
					continue;
				}
				try {
					entries.add(new DirectorySnapshots.Entry(name, withTypes ? typeOf(readFileAttributes(child)) : null));
				} catch (NoSuchFileException e) {
					// vanished while listing
				}
			}
		} catch (DirectoryIteratorException e) {
			throw e.getCause();
		}
		return entries;
	}

	/**
	 * Deletes the entries of a directory that holds nothing but hidden files, which looks empty to the caller.
	 *
	 * @throws DirectoryNotEmptyException If the directory holds a visible entry or a hidden directory. Nothing is deleted then.
	 */
	private void deleteHiddenEntries(Path directory) throws IOException {
		List<Path> hidden = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
			for (Path child : stream) {
				if (!HiddenNames.contains(child.getFileName().toString()) || readFileAttributes(child).isDirectory()) {
					throw new DirectoryNotEmptyException(directory.toString());
				}
				hidden.add(child);
			}
		} catch (DirectoryIteratorException e) {
			throw e.getCause();
		}
		for (Path child : hidden) {
			Files.delete(child);
		}
	}

	/**
	 * @return The index of the first entry a request asks for in a listing of that length
	 */
	static int firstIndex(ReaddirRequest request, int listingLength) throws StatusException {
		if (Long.compareUnsigned(request.cookie(), listingLength) > 0) {
			throw new StatusException(Messages.STATUS_INVALID_COOKIE);
		}
		int index = (int) request.cookie();
		return request.wantAttributes() ? Math.max(index, FIRST_CHILD_INDEX) : index;
	}

	private @Nullable DirectoryEntry resolve(Node directory, DirectorySnapshots.Entry entry, int index, boolean wantAttributes) throws IOException {
		long nextCookie = index + 1;
		if (index < FIRST_CHILD_INDEX) {
			// the root is its own parent here, as FSKit expects of a volume's root
			boolean self = index == 0 || directory.id == Messages.ROOT_NODE_ID;
			return new DirectoryEntry(entry.name(), entry.type(), self ? directory.id : directory.parentId, nextCookie, null);
		}
		Path path = directory.path.resolve(entry.name());
		if (!wantAttributes && entry.type() != null) {
			return new DirectoryEntry(entry.name(), entry.type(), nodes.list(path, entry.type(), directory).id, nextCookie, null);
		}
		try (var _ = locks.sample(path)) {
			BasicFileAttributes attributes = readFileAttributes(path);
			Node node = nodes.list(path, typeOf(attributes), directory);
			Attributes published = publish(node, attributes);
			return new DirectoryEntry(entry.name(), published.type(), node.id, nextCookie, wantAttributes ? published : null);
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	/* channels */

	private static boolean needsOpening(Node node, int modes) {
		return node.type == NodeType.FILE && (node.channel == null || (node.modes | modes) != node.modes);
	}

	/**
	 * @return The node's channel, opened with at least the given modes. The caller holds the node's data lock, for writing if the channel may have to be opened or widened.
	 */
	private FileChannel ensureOpen(Node node, int modes) throws IOException {
		if (node.type != NodeType.FILE) {
			throw new StatusException(node.type == NodeType.DIRECTORY ? Errno.EISDIR : Errno.ENOTSUP);
		}
		if (needsOpening(node, modes)) {
			int widened = node.modes | modes;
			if (readOnly && (widened & Messages.MODE_WRITE) != 0) {
				throw new StatusException(Errno.EROFS);
			}
			if (node.unlinked) {
				// the path is gone, so a removed item is stuck with the channel it has
				throw new StatusException(Errno.EIO);
			}
			if (!nodes.isHeld(node)) {
				// a node that was only listed leaves the table with the last listing of its directory, which would drop its channel unclosed
				throw new StatusException(Errno.ESTALE);
			}
			// a channel cannot be upgraded in place, so open one with the widened modes and swap it in
			FileChannel opened = openChannel(node.path, openOptions(widened));
			FileChannel previous = node.channel;
			nodes.setChannel(node, opened, widened);
			if (previous != null) {
				previous.close();
			}
		}
		return node.channel;
	}

	private static Set<OpenOption> openOptions(int modes) {
		Set<OpenOption> options = new HashSet<>();
		options.add(LinkOption.NOFOLLOW_LINKS);
		if ((modes & Messages.MODE_WRITE) != 0) {
			options.add(StandardOpenOption.WRITE);
		}
		if ((modes & Messages.MODE_READ) != 0 || modes == 0) {
			options.add(StandardOpenOption.READ);
		}
		return options;
	}

	private void truncateOrExpand(Node node, long size) throws IOException {
		if (node.channel != null && (node.modes & Messages.MODE_WRITE) != 0) {
			FileChannelUtil.truncateOrExpand(node.channel, size);
		} else if (node.unlinked) {
			throw new StatusException(Errno.EINVAL);
		} else {
			try (FileChannel channel = openChannel(node.path, openOptions(Messages.MODE_WRITE))) {
				FileChannelUtil.truncateOrExpand(channel, size);
			}
		}
	}

	/* attributes */

	@FunctionalInterface
	private interface Sample<E extends Exception> {

		Attributes take() throws E;
	}

	/**
	 * Takes a sample of a node's attributes under the sampling lock of its entry, or under the node's monitor once it is unlinked, since its path then names nothing or another entry.
	 */
	private <E extends Exception> Attributes sampled(Node node, Sample<E> sample) throws E {
		if (node.unlinked) {
			synchronized (node) {
				return sample.take();
			}
		}
		try (var _ = locks.sample(node.path)) {
			return sample.take();
		}
	}

	private Attributes refresh(Node node) throws IOException {
		return sampled(node, () -> {
			if (node.unlinked) {
				// nothing can be read by path anymore; only the size still changes, through the open channel
				Attributes last = node.attributes;
				long size = node.channel != null ? node.channel.size() : last.size();
				node.attributes = new Attributes(last.type(), last.mode(), size, last.nodeId(), last.parentId(), last.modified(), last.accessed(), last.created(), generations.incrementAndGet());
				return node.attributes;
			}
			return publish(node, readFileAttributes(node.path));
		});
	}

	/**
	 * Stamps attributes read under the entry's sampling lock with a generation and makes them the node's. Called under that lock.
	 */
	private Attributes publish(Node node, BasicFileAttributes attributes) {
		nodes.setType(node, typeOf(attributes));
		node.attributes = toAttributes(node, attributes, generations.incrementAndGet());
		return node.attributes;
	}

	/**
	 * Reads a node's attributes for the reply to a change that has already been applied. Such a reply must not turn into a failure, so if reading fails, the last known attributes are used.
	 *
	 * @param established Applies to the last known attributes what the change is known to have established
	 */
	private Attributes refreshAfterChange(Node node, UnaryOperator<Attributes> established) {
		try {
			return refresh(node);
		} catch (IOException | RuntimeException e) {
			FailureLog.warn(LOG, "Unable to read the attributes of node " + node.id + " after changing it. Replying with its last known attributes.", e);
			return sampled(node, () -> {
				Timestamp now = now();
				Attributes last = node.attributes != null ? node.attributes : new Attributes(node.type, node.type == NodeType.DIRECTORY ? DEFAULT_DIRECTORY_MODE : DEFAULT_FILE_MODE, 0, node.id, node.parentId, now, now, now, 0);
				// derived from what the change established, so it is as new as a sample taken now
				node.attributes = withGeneration(established.apply(last), generations.incrementAndGet());
				return node.attributes;
			});
		}
	}

	private static Attributes withGeneration(Attributes attributes, long generation) {
		return new Attributes(attributes.type(), attributes.mode(), attributes.size(), attributes.nodeId(), attributes.parentId(), attributes.modified(), attributes.accessed(), attributes.created(), generation);
	}

	/**
	 * Reads the usable space. Its generation is drawn first, so that the sample reflects every change completed before.
	 */
	private FreeSpace sampleFreeSpace() throws IOException {
		long generation = generations.incrementAndGet();
		return new FreeSpace(readUsableSpace(), generation);
	}

	/**
	 * Samples the usable space for the reply to a change, which must not turn into a failure.
	 */
	private FreeSpace freeSpace() {
		try {
			return sampleFreeSpace();
		} catch (IOException | RuntimeException e) {
			FailureLog.warn(LOG, "Unable to read the usable space of the backing store.", e);
			return new FreeSpace(Messages.UNKNOWN_USABLE_BYTES, 0);
		}
	}

	private Attributes toAttributes(Node node, BasicFileAttributes attributes, long generation) {
		NodeType type = typeOf(attributes);
		int mode;
		if (attributes instanceof PosixFileAttributes posixAttributes) {
			mode = FileAttributesUtil.posixPermissionsToOctalMode(posixAttributes.permissions());
		} else {
			mode = type == NodeType.DIRECTORY ? DEFAULT_DIRECTORY_MODE : DEFAULT_FILE_MODE;
		}
		return new Attributes(type, mode, attributes.size(), node.id, node.parentId, timestamp(attributes.lastModifiedTime()), timestamp(attributes.lastAccessTime()), timestamp(attributes.creationTime()), generation);
	}

	private static NodeType typeOf(BasicFileAttributes attributes) {
		if (attributes.isDirectory()) {
			return NodeType.DIRECTORY;
		} else if (attributes.isSymbolicLink()) {
			return NodeType.SYMLINK;
		} else {
			return NodeType.FILE;
		}
	}

	private static Timestamp timestamp(FileTime time) {
		Instant instant = time.toInstant();
		return new Timestamp(instant.getEpochSecond(), instant.getNano());
	}

	private static FileTime fileTime(Timestamp timestamp) throws StatusException {
		// nanoseconds beyond what an int holds arrive negative
		if (timestamp.nanos() < 0 || timestamp.nanos() >= 1_000_000_000) {
			throw new StatusException(Errno.EINVAL);
		}
		try {
			return FileTime.from(Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos()));
		} catch (DateTimeException e) {
			// beyond what an Instant holds
			throw new StatusException(Errno.EINVAL);
		}
	}

	private static Timestamp now() {
		return timestamp(FileTime.from(Instant.now()));
	}

	/* seams for tests */

	/**
	 * Reports a failure that a request is answered with EIO for, although no status was meant for it.
	 */
	void reportUnexpectedFailure(Request request, Exception e) {
		FailureLog.warn(LOG, request.opcode() + " returns EIO.", e);
	}

	/**
	 * Acquires a lock set, once an operation has read the paths it builds it from.
	 */
	PathLocks.Held lock(PathLocks.Requirement... requirements) {
		return locks.lock(requirements);
	}

	Node hold(Path storedPath, NodeType type, Node parent) {
		return nodes.hold(storedPath, type, parent);
	}

	/**
	 * Reads an entry's attributes without following a symbolic link.
	 */
	BasicFileAttributes readFileAttributes(Path path) throws IOException {
		if (posix) {
			return Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} else {
			return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
	}

	long readUsableSpace() throws IOException {
		return fileStore.getUsableSpace();
	}

	FileChannel openChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
		return FileChannel.open(path, options, attributes);
	}

	void move(Path source, Path target, CopyOption... options) throws IOException {
		Files.move(source, target, options);
	}

	/**
	 * Sets an entry's permissions, following a symbolic link, so the entry must not be one. The variant with {@code NOFOLLOW_LINKS} is not used because it has to open the entry, which fails for a file that may be neither read nor written and for a directory that may not be read.
	 */
	void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
		Files.setPosixFilePermissions(path, permissions);
	}

	/**
	 * Sets an entry's times without following a symbolic link. A time that is {@code null} stays as it is.
	 */
	void setTimes(Path path, @Nullable FileTime modified, @Nullable FileTime accessed) throws IOException {
		Files.getFileAttributeView(path, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).setTimes(modified, accessed, null);
	}

	/**
	 * Resolves a path without following any symbolic link.
	 */
	Path toRealPath(Path path) throws IOException {
		return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
	}
}
