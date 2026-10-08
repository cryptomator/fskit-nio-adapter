package org.cryptomator.frontend.fskit.fs;

import org.cryptomator.frontend.fskit.protocol.Messages;
import org.cryptomator.frontend.fskit.protocol.Messages.Attributes;
import org.cryptomator.frontend.fskit.protocol.Messages.CloseRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.CloseResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.CreateRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.DirectoryEntry;
import org.cryptomator.frontend.fskit.protocol.Messages.ForgetRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.ForgetResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.FreeSpace;
import org.cryptomator.frontend.fskit.protocol.Messages.GetattrRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.GetattrResponse;
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
import org.cryptomator.frontend.fskit.protocol.Messages.RemoveRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.RenameRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.Request;
import org.cryptomator.frontend.fskit.protocol.Messages.Response;
import org.cryptomator.frontend.fskit.protocol.Messages.SetattrRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.SymlinkRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.Timestamp;
import org.cryptomator.frontend.fskit.protocol.Messages.WriteRequest;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Shows a directory {@code .fseventsd} with an empty file {@code no_log} in the root of a volume, without storing either. The root's listing leaves the directory out.
 * <p>
 * On a volume without that file, macOS creates the directory and keeps a log of the volume's file system events in it, which would end up in the mounted path. A file {@code ignore} would keep it from doing so as well, but would also stop the delivery of events as they happen.
 * <p>
 * An entry named {@code .fseventsd} that the mounted path already holds cannot be reached through the volume and stays as it is, since the shown directory takes its name.
 */
@SuppressWarnings("OctalInteger")
final class EventLogOptOut {

	private static final String DIRECTORY_NAME = ".fseventsd";
	private static final String FILE_NAME = "no_log";
	// below the ids that NodeTable assigns
	private static final long DIRECTORY_ID = 3;
	private static final long FILE_ID = 4;
	private static final long VERIFIER = 1;
	private static final FreeSpace UNKNOWN_FREE_SPACE = new FreeSpace(Messages.UNKNOWN_USABLE_BYTES, 0);

	private final Attributes directory;
	private final Attributes file;

	EventLogOptOut(Timestamp created) {
		this.directory = new Attributes(NodeType.DIRECTORY, 0555, 0, DIRECTORY_ID, Messages.ROOT_NODE_ID, created, created, created, 0);
		this.file = new Attributes(NodeType.FILE, 0444, 0, FILE_ID, DIRECTORY_ID, created, created, created, 0);
	}

	/**
	 * @return Whether the name in that directory belongs to the shown directory, so that a stored entry of that name is passed over
	 */
	static boolean takes(long parentId, String name) {
		return parentId == Messages.ROOT_NODE_ID && name.equals(DIRECTORY_NAME);
	}

	private static boolean shows(long nodeId) {
		return nodeId == DIRECTORY_ID || nodeId == FILE_ID;
	}

	/**
	 * @return The response to a request the two items answer, or {@code null} for a request that is left to the stored entries
	 */
	@Nullable Response handle(Request request) throws StatusException {
		return switch (request) {
			case LookupRequest r when takes(r.parentId(), r.name()) -> new LookupResponse(directory, DIRECTORY_NAME);
			case LookupRequest r when r.parentId() == DIRECTORY_ID -> {
				if (!r.name().equals(FILE_NAME)) {
					throw new StatusException(Errno.ENOENT);
				}
				yield new LookupResponse(file, FILE_NAME);
			}
			case GetattrRequest r when shows(r.nodeId()) -> new GetattrResponse(r.nodeId() == DIRECTORY_ID ? directory : file);
			case ReaddirRequest r when r.nodeId() == DIRECTORY_ID -> list(r);
			case OpenRequest r when shows(r.nodeId()) -> {
				if ((r.modes() & Messages.MODE_WRITE) != 0) {
					throw new StatusException(Errno.EPERM);
				}
				yield new OpenResponse();
			}
			case ReadRequest r when r.nodeId() == FILE_ID -> new ReadResponse(file, ByteBuffer.allocate(0));
			case ReadlinkRequest r when shows(r.nodeId()) -> throw new StatusException(Errno.EINVAL);
			case CloseRequest r when shows(r.nodeId()) -> new CloseResponse(UNKNOWN_FREE_SPACE);
			case ForgetRequest r when shows(r.nodeId()) -> new ForgetResponse(UNKNOWN_FREE_SPACE);
			case CreateRequest r when takes(r.parentId(), r.name()) -> throw new StatusException(Errno.EEXIST);
			case CreateRequest r when shows(r.parentId()) -> throw new StatusException(Errno.EPERM);
			case SymlinkRequest r when takes(r.parentId(), r.name()) -> throw new StatusException(Errno.EEXIST);
			case SymlinkRequest r when shows(r.parentId()) -> throw new StatusException(Errno.EPERM);
			case SetattrRequest r when shows(r.nodeId()) -> throw new StatusException(Errno.EPERM);
			case RemoveRequest r when shows(r.nodeId()) -> throw new StatusException(Errno.EPERM);
			case RenameRequest r when shows(r.nodeId()) || shows(r.destinationParentId()) || takes(r.destinationParentId(), r.destinationName()) -> throw new StatusException(Errno.EPERM);
			case WriteRequest r when shows(r.nodeId()) -> throw new StatusException(Errno.EPERM);
			default -> null;
		};
	}

	private ReaddirResponse list(ReaddirRequest request) throws StatusException {
		List<DirectoryEntry> entries = List.of( //
				new DirectoryEntry(".", NodeType.DIRECTORY, DIRECTORY_ID, 1, null), //
				new DirectoryEntry("..", NodeType.DIRECTORY, Messages.ROOT_NODE_ID, 2, null), //
				new DirectoryEntry(FILE_NAME, NodeType.FILE, FILE_ID, 3, request.wantAttributes() ? file : null));
		if (request.cookie() != 0 && request.verifier() != VERIFIER) {
			throw new StatusException(Messages.STATUS_INVALID_COOKIE);
		}
		return new ReaddirResponse(VERIFIER, false, entries.subList(FileSystemOperations.firstIndex(request, entries.size()), entries.size()));
	}
}
