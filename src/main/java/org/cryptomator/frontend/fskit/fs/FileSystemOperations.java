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
import java.nio.file.AccessDeniedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Serves the operations of one mounted volume against a root {@link Path}.
 * <p>
 * Instances take no locks: after construction, the session's request thread is the only one to call them. Compound operations such as truncate-and-extend rely on this.
 */
@SuppressWarnings("OctalInteger")
public class FileSystemOperations implements Closeable {

	private static final Logger LOG = LoggerFactory.getLogger(FileSystemOperations.class);
	private static final int DEFAULT_FILE_MODE = 0644;
	private static final int DEFAULT_DIRECTORY_MODE = 0755;
	private static final int FIRST_CHILD_INDEX = 2; // listings start with "." and ".."

	private final boolean readOnly;
	private final FileStore fileStore;
	private final boolean posix;
	private final NodeTable nodes;
	private final DirectorySnapshots snapshots;

	public FileSystemOperations(Path root, boolean readOnly) throws IOException {
		this.readOnly = readOnly;
		this.fileStore = Files.getFileStore(root);
		this.posix = fileStore.supportsFileAttributeView(PosixFileAttributeView.class);
		this.nodes = new NodeTable(root);
		this.snapshots = new DirectorySnapshots(nodes);
	}

	public Response handle(Request request) {
		try {
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
			};
		} catch (IOException | RuntimeException e) {
			int status = Errno.of(e);
			if (status == Errno.EIO && !(e instanceof StatusException)) {
				FailureLog.warn(LOG, request.opcode() + " returns EIO.", e);
			}
			return new Failure(status);
		}
	}

	/**
	 * Closes every open channel and forgets every node.
	 */
	@Override
	public void close() {
		nodes.clear();
	}

	/* operations */

	private StatfsResponse statfs() throws IOException {
		return new StatfsResponse(fileStore.getTotalSpace(), readUsableSpace());
	}

	private LookupResponse lookup(LookupRequest request) throws IOException {
		Node directory = linkedDirectory(request.parentId());
		Child child = findChild(directory, request.name());
		if (child == null || !child.sameName()) {
			throw new NoSuchFileException(request.name());
		}
		Node node = nodes.hold(child.path(), typeOf(child.attributes()), directory);
		node.attributes = toAttributes(node, child.attributes());
		return new LookupResponse(node.attributes, name(node));
	}

	private ForgetResponse forget(ForgetRequest request) throws IOException {
		nodes.forget(request.nodeId());
		return new ForgetResponse(usableBytes());
	}

	private GetattrResponse getattr(GetattrRequest request) throws IOException {
		return new GetattrResponse(refresh(nodes.get(request.nodeId())));
	}

	private SetattrResponse setattr(SetattrRequest request) throws IOException {
		assertWritable();
		Node node = nodes.get(request.nodeId());
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
				last.created()));
		return new SetattrResponse(applied, attributes, usableBytes());
	}

	private ReaddirResponse readdir(ReaddirRequest request) throws IOException {
		Node directory = linkedDirectory(request.nodeId());
		DirectorySnapshots.Snapshot snapshot = request.cookie() == 0 ? snapshots.add(directory.id, list(directory)) : snapshots.get(directory.id, request.verifier());
		if (snapshot == null || Long.compareUnsigned(request.cookie(), snapshot.entries().size()) > 0) {
			throw new StatusException(Messages.STATUS_INVALID_COOKIE);
		}
		int index = (int) request.cookie();
		if (request.wantAttributes()) {
			index = Math.max(index, FIRST_CHILD_INDEX);
		}
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
		Node directory = linkedDirectory(request.parentId());
		checkName(request.name());
		Path target = directory.path.resolve(compose(request.name()));
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
		snapshots.invalidate(directory.id);
		Node node = nodes.hold(storedPathOrElse(target), request.type(), directory);
		if (channel != null) {
			node.channel = channel;
			node.modes = Messages.MODE_READ | Messages.MODE_WRITE;
		}
		Timestamp now = now();
		Attributes attributes = refreshAfterChange(node, _ -> new Attributes(request.type(), request.mode(), 0, node.id, node.parentId, now, now, now));
		return new CreateResponse(attributes, name(node), refreshAfterChange(directory, UnaryOperator.identity()), usableBytes());
	}

	// the mode an entry is created with is cut down by this process's umask, although the kernel has applied the caller's already
	private void applyExactly(Path created, Set<PosixFilePermission> permissions) {
		try {
			setPermissions(created, permissions);
		} catch (IOException | RuntimeException e) {
			FailureLog.warn(LOG, "Unable to set the permissions of a created entry.", e);
		}
	}

	private RemoveResponse remove(RemoveRequest request) throws IOException {
		assertWritable();
		Node node = linked(request.nodeId());
		Node directory = linkedDirectory(request.parentId());
		Attributes attributes = refresh(node);
		Files.delete(node.path);
		nodes.unlink(node);
		snapshots.invalidate(directory.id);
		return new RemoveResponse(attributes, refreshAfterChange(directory, UnaryOperator.identity()), usableBytes());
	}

	private RenameResponse rename(RenameRequest request) throws IOException {
		assertWritable();
		Node node = linked(request.nodeId());
		Node sourceDirectory = linkedDirectory(request.sourceParentId());
		Node destinationDirectory = linkedDirectory(request.destinationParentId());
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
		if (replaced != null) {
			replaced.attributes = toAttributes(replaced, other.attributes());
		}
		Path target = existing != null && existing.sameName() ? existing.path() : destinationDirectory.path.resolve(compose(request.destinationName()));
		try {
			// A move that is not atomic may copy the source and delete it, which leaves an open channel on the deleted file.
			// It also deletes the target before anything has taken its place.
			// Where the atomic move of the first two cases is impossible, as across file stores below the root, the reply is EXDEV and the caller copies, as it does between volumes.
			if (other == null) {
				move(node.path, target, StandardCopyOption.ATOMIC_MOVE);
			} else if (other.attributes().isRegularFile() && node.type == NodeType.FILE) {
				move(node.path, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} else {
				// cryptofs cannot replace a directory or a link in one step
				move(node.path, target, StandardCopyOption.REPLACE_EXISTING);
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
		snapshots.invalidateAll();
		Attributes attributes = refreshAfterChange(node, last -> new Attributes(last.type(), last.mode(), last.size(), last.nodeId(), destinationDirectory.id, last.modified(), last.accessed(), last.created()));
		return new RenameResponse(name(node), attributes, refreshAfterChange(sourceDirectory, UnaryOperator.identity()), refreshAfterChange(destinationDirectory, UnaryOperator.identity()), replaced != null ? replaced.attributes : null, usableBytes());
	}

	private OpenResponse open(OpenRequest request) throws IOException {
		Node node = nodes.get(request.nodeId());
		if (node.type != NodeType.DIRECTORY) {
			ensureOpen(node, request.modes() & (Messages.MODE_READ | Messages.MODE_WRITE));
		}
		return new OpenResponse();
	}

	private CloseResponse close(CloseRequest request) throws IOException {
		Node node = nodes.get(request.nodeId());
		if ((request.keptModes() & (Messages.MODE_READ | Messages.MODE_WRITE)) == 0) {
			node.closeChannel();
		}
		return new CloseResponse(usableBytes());
	}

	private ReadResponse read(ReadRequest request) throws IOException {
		Node node = nodes.get(request.nodeId());
		if (request.offset() < 0 || request.length() < 0 || request.length() > FrameCodec.MAX_PAYLOAD_LENGTH) {
			throw new StatusException(Errno.EINVAL);
		}
		FileChannel channel = ensureOpen(node, Messages.MODE_READ);
		ByteBuffer data = ByteBuffer.allocate(request.length());
		while (data.hasRemaining() && channel.read(data, request.offset() + data.position()) >= 0) {
			// read until the buffer is full or the file ends
		}
		return new ReadResponse(refresh(node), data.flip());
	}

	private WriteResponse write(WriteRequest request) throws IOException {
		assertWritable();
		Node node = nodes.get(request.nodeId());
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
		Attributes attributes = refreshAfterChange(node, last -> new Attributes(last.type(), last.mode(), Math.max(last.size(), end), last.nodeId(), last.parentId(), now(), last.accessed(), last.created()));
		return new WriteResponse(length, attributes, usableBytes());
	}

	private SyncResponse sync() throws IOException {
		IOException firstFailure = null;
		for (Node node : nodes.all()) {
			if (node.channel != null) {
				try {
					node.channel.force(false);
				} catch (IOException e) {
					firstFailure = firstFailure != null ? firstFailure : e;
				}
			}
		}
		if (firstFailure != null) {
			throw firstFailure;
		}
		return new SyncResponse(usableBytes());
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

	/* names */

	/**
	 * @param path     The entry's path as the backing file system stores it
	 * @param sameName Whether the stored name is the requested one, disregarding Unicode normalization. If not, they differ in case and the store is case-insensitive.
	 */
	private record Child(Path path, BasicFileAttributes attributes, boolean sameName) {
	}

	private @Nullable Child findChild(Node directory, String name) throws IOException {
		checkName(name);
		String composed = compose(name);
		for (String spelling : composed.equals(name) ? List.of(name) : List.of(name, composed)) {
			Path candidate = directory.path.resolve(spelling);
			try {
				BasicFileAttributes attributes = readFileAttributes(candidate);
				Path stored = storedPath(candidate);
				return new Child(stored, attributes, compose(stored.getFileName().toString()).equals(composed));
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

	private static String compose(String name) {
		return Normalizer.normalize(name, Normalizer.Form.NFC);
	}

	// with NOFOLLOW_LINKS, toRealPath returns each name in the case and Unicode form the backing file system stores
	private Path storedPath(Path path) throws IOException {
		try {
			return path.resolveSibling(toRealPath(path).getFileName());
		} catch (AccessDeniedException e) {
			// on macOS toRealPath reads every ancestor directory, which fails below one that may be searched but not read, as .Trashes is
			return storedPathAccordingToParent(path);
		}
	}

	private static Path storedPathAccordingToParent(Path path) throws IOException {
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
			// nothing can tell the stored spelling of an entry in a directory that cannot be read
			return path;
		} catch (DirectoryIteratorException e) {
			throw e.getCause();
		}
		return differentSpelling != null ? path.resolveSibling(differentSpelling.getFileName()) : path;
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

	private List<DirectorySnapshots.Entry> list(Node directory) throws IOException {
		List<DirectorySnapshots.Entry> entries = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory.path)) {
			for (Path child : stream) {
				try {
					entries.add(new DirectorySnapshots.Entry(child.getFileName().toString(), typeOf(readFileAttributes(child))));
				} catch (NoSuchFileException e) {
					// vanished while listing
				}
			}
		} catch (DirectoryIteratorException e) {
			throw e.getCause();
		}
		return entries;
	}

	private @Nullable DirectoryEntry resolve(Node directory, DirectorySnapshots.Entry entry, int index, boolean wantAttributes) throws IOException {
		long nextCookie = index + 1;
		if (index < FIRST_CHILD_INDEX) {
			// the root is its own parent here, as FSKit expects of a volume's root
			boolean self = index == 0 || directory.id == Messages.ROOT_NODE_ID;
			return new DirectoryEntry(entry.name(), entry.type(), self ? directory.id : directory.parentId, nextCookie, null);
		}
		Path path = directory.path.resolve(entry.name());
		if (!wantAttributes) {
			return new DirectoryEntry(entry.name(), entry.type(), nodes.list(path, entry.type(), directory).id, nextCookie, null);
		}
		try {
			BasicFileAttributes attributes = readFileAttributes(path);
			Node node = nodes.list(path, typeOf(attributes), directory);
			node.attributes = toAttributes(node, attributes);
			return new DirectoryEntry(entry.name(), node.type, node.id, nextCookie, node.attributes);
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	/* channels */

	private FileChannel ensureOpen(Node node, int modes) throws IOException {
		if (node.type != NodeType.FILE) {
			throw new StatusException(node.type == NodeType.DIRECTORY ? Errno.EISDIR : Errno.ENOTSUP);
		}
		int widened = node.modes | modes;
		if (node.channel == null || widened != node.modes) {
			if (readOnly && (widened & Messages.MODE_WRITE) != 0) {
				throw new StatusException(Errno.EROFS);
			}
			if (node.unlinked) {
				// the path is gone, so a removed item is stuck with the channel it has
				throw new StatusException(Errno.EIO);
			}
			// a channel cannot be upgraded in place, so open one with the widened modes and swap it in
			FileChannel opened = openChannel(node.path, openOptions(widened));
			FileChannel previous = node.channel;
			node.channel = opened;
			node.modes = widened;
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

	private Attributes refresh(Node node) throws IOException {
		if (node.unlinked) {
			// nothing can be read by path anymore; only the size still changes, through the open channel
			Attributes last = node.attributes;
			long size = node.channel != null ? node.channel.size() : last.size();
			node.attributes = new Attributes(last.type(), last.mode(), size, last.nodeId(), last.parentId(), last.modified(), last.accessed(), last.created());
			return node.attributes;
		}
		BasicFileAttributes attributes = readFileAttributes(node.path);
		node.type = typeOf(attributes);
		node.attributes = toAttributes(node, attributes);
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
			Timestamp now = now();
			Attributes last = node.attributes != null ? node.attributes : new Attributes(node.type, node.type == NodeType.DIRECTORY ? DEFAULT_DIRECTORY_MODE : DEFAULT_FILE_MODE, 0, node.id, node.parentId, now, now, now);
			node.attributes = established.apply(last);
			return node.attributes;
		}
	}

	private long usableBytes() {
		try {
			return readUsableSpace();
		} catch (IOException | RuntimeException e) {
			FailureLog.warn(LOG, "Unable to read the usable space of the backing store.", e);
			return Messages.UNKNOWN_USABLE_BYTES;
		}
	}

	private Attributes toAttributes(Node node, BasicFileAttributes attributes) {
		NodeType type = typeOf(attributes);
		int mode;
		if (attributes instanceof PosixFileAttributes posixAttributes) {
			mode = FileAttributesUtil.posixPermissionsToOctalMode(posixAttributes.permissions());
		} else {
			mode = type == NodeType.DIRECTORY ? DEFAULT_DIRECTORY_MODE : DEFAULT_FILE_MODE;
		}
		return new Attributes(type, mode, attributes.size(), node.id, node.parentId, timestamp(attributes.lastModifiedTime()), timestamp(attributes.lastAccessTime()), timestamp(attributes.creationTime()));
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
