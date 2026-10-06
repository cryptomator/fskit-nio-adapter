package org.cryptomator.frontend.fskit.protocol;

import org.jetbrains.annotations.Nullable;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The messages of the bridge protocol, as specified in {@code protocol/PROTOCOL.md}.
 */
public final class Messages {

	public static final int MAGIC = 0x46534B4E;
	public static final int PROTOCOL_VERSION = 1;
	public static final int TOKEN_LENGTH = 32;

	public static final long PARENT_OF_ROOT_NODE_ID = 1;
	public static final long ROOT_NODE_ID = 2;
	public static final long FIRST_ASSIGNED_NODE_ID = 64;

	public static final int STATUS_INVALID_COOKIE = -1;
	public static final long UNKNOWN_USABLE_BYTES = -1L;

	public static final int MODE_READ = 1;
	public static final int MODE_WRITE = 2;

	public static final int ATTRIBUTE_SIZE = 1;
	public static final int ATTRIBUTE_MODE = 2;
	public static final int ATTRIBUTE_ACCESSED = 4;
	public static final int ATTRIBUTE_MODIFIED = 8;

	private static final ByteBuffer EMPTY = ByteBuffer.allocate(0).asReadOnlyBuffer();
	// A control section's length is known only once it is encoded, so it is encoded into a buffer of the maximum length. Each thread reuses its buffer, which spares allocating and zeroing that much for every frame.
	private static final ThreadLocal<ByteBuffer> CONTROL_SCRATCH = ThreadLocal.withInitial(() -> ByteBuffer.allocate(FrameCodec.MAX_CONTROL_LENGTH));

	private Messages() {
	}

	public static Request decodeRequest(Frame frame) throws ProtocolException {
		if (frame.kind() != Frame.Kind.REQUEST) {
			throw new ProtocolException("Not a request");
		}
		ByteBuffer control = frame.control().duplicate();
		ByteBuffer payload = frame.payload().duplicate();
		try {
			Request request = switch (frame.opcode()) {
				case HELLO -> HelloRequest.decode(control);
				case STATFS -> new StatfsRequest();
				case LOOKUP -> LookupRequest.decode(control);
				case FORGET -> ForgetRequest.decode(control);
				case GETATTR -> GetattrRequest.decode(control);
				case SETATTR -> SetattrRequest.decode(control);
				case READDIR -> ReaddirRequest.decode(control);
				case CREATE -> CreateRequest.decode(control);
				case REMOVE -> RemoveRequest.decode(control);
				case RENAME -> RenameRequest.decode(control);
				case OPEN -> OpenRequest.decode(control);
				case CLOSE -> CloseRequest.decode(control);
				case READ -> ReadRequest.decode(control);
				case WRITE -> WriteRequest.decode(control, payload);
				case SYNC -> new SyncRequest();
				case READLINK -> ReadlinkRequest.decode(control);
				case SYMLINK -> SymlinkRequest.decode(control);
			};
			assertConsumed(control, frame.opcode() == Opcode.WRITE ? EMPTY : payload);
			return request;
		} catch (BufferUnderflowException e) {
			throw new ProtocolException("Truncated " + frame.opcode() + " request", e);
		}
	}

	public static Response decodeResponse(Frame frame) throws ProtocolException {
		if (frame.kind() != Frame.Kind.RESPONSE) {
			throw new ProtocolException("Not a response");
		}
		ByteBuffer control = frame.control().duplicate();
		ByteBuffer payload = frame.payload().duplicate();
		try {
			int status = control.getInt();
			if (status != 0) {
				assertConsumed(control, payload);
				return new Failure(status);
			}
			Response response = switch (frame.opcode()) {
				case HELLO -> new HelloResponse();
				case STATFS -> StatfsResponse.decode(control);
				case LOOKUP -> LookupResponse.decode(control);
				case FORGET -> ForgetResponse.decode(control);
				case GETATTR -> GetattrResponse.decode(control);
				case SETATTR -> SetattrResponse.decode(control);
				case READDIR -> ReaddirResponse.decode(control);
				case CREATE -> CreateResponse.decode(control);
				case REMOVE -> RemoveResponse.decode(control);
				case RENAME -> RenameResponse.decode(control);
				case OPEN -> new OpenResponse();
				case CLOSE -> CloseResponse.decode(control);
				case READ -> ReadResponse.decode(control, payload);
				case WRITE -> WriteResponse.decode(control);
				case SYNC -> SyncResponse.decode(control);
				case READLINK -> ReadlinkResponse.decode(control);
				case SYMLINK -> SymlinkResponse.decode(control);
			};
			assertConsumed(control, frame.opcode() == Opcode.READ ? EMPTY : payload);
			return response;
		} catch (BufferUnderflowException e) {
			throw new ProtocolException("Truncated " + frame.opcode() + " response", e);
		}
	}

	private static void assertConsumed(ByteBuffer control, ByteBuffer payload) throws ProtocolException {
		if (control.hasRemaining()) {
			throw new ProtocolException(control.remaining() + " bytes left over in control section");
		}
		if (payload.hasRemaining()) {
			throw new ProtocolException("Unexpected payload of " + payload.remaining() + " bytes");
		}
	}

	private static Frame frame(Frame.Kind kind, Opcode opcode, long requestId, ControlEncoder encoder, ByteBuffer payload) {
		ByteBuffer scratch = CONTROL_SCRATCH.get().clear();
		encoder.encode(scratch);
		ByteBuffer control = ByteBuffer.allocate(scratch.position()).put(scratch.flip()).flip();
		return new Frame(kind, opcode, requestId, control, payload);
	}

	@FunctionalInterface
	private interface ControlEncoder {

		void encode(ByteBuffer control);
	}

	/* shared records */

	public enum NodeType {
		FILE(1),
		DIRECTORY(2),
		SYMLINK(3);

		private final int wireValue;

		NodeType(int wireValue) {
			this.wireValue = wireValue;
		}

		void encode(ByteBuffer buffer) {
			buffer.put((byte) wireValue);
		}

		static NodeType decode(ByteBuffer buffer) throws ProtocolException {
			int wireValue = Byte.toUnsignedInt(buffer.get());
			for (NodeType type : values()) {
				if (type.wireValue == wireValue) {
					return type;
				}
			}
			throw new ProtocolException("Unknown node type " + wireValue);
		}
	}

	public record Timestamp(long seconds, int nanos) {

		void encode(ByteBuffer buffer) {
			buffer.putLong(seconds).putInt(nanos);
		}

		static Timestamp decode(ByteBuffer buffer) {
			return new Timestamp(buffer.getLong(), buffer.getInt());
		}
	}

	public record Attributes(NodeType type, int mode, long size, long nodeId, long parentId, Timestamp modified, Timestamp accessed, Timestamp created) {

		static final int ENCODED_LENGTH = 1 + 2 + 8 + 8 + 8 + 3 * (8 + 4);

		void encode(ByteBuffer buffer) {
			type.encode(buffer);
			buffer.putShort((short) mode).putLong(size).putLong(nodeId).putLong(parentId);
			modified.encode(buffer);
			accessed.encode(buffer);
			created.encode(buffer);
		}

		static Attributes decode(ByteBuffer buffer) throws ProtocolException {
			return new Attributes(NodeType.decode(buffer), Short.toUnsignedInt(buffer.getShort()), buffer.getLong(), buffer.getLong(), buffer.getLong(), Timestamp.decode(buffer), Timestamp.decode(buffer), Timestamp.decode(buffer));
		}
	}

	public record DirectoryEntry(String name, NodeType type, long nodeId, long nextCookie, @Nullable Attributes attributes) {

		/**
		 * The most bytes an entry with the given name can occupy in a control section.
		 */
		public static int maxEncodedLength(String name) {
			return Wire.maxEncodedLength(name) + 1 + 8 + 8 + 1 + Attributes.ENCODED_LENGTH;
		}

		void encode(ByteBuffer buffer) {
			Wire.putString(buffer, name);
			type.encode(buffer);
			buffer.putLong(nodeId).putLong(nextCookie);
			Wire.putBoolean(buffer, attributes != null);
			if (attributes != null) {
				attributes.encode(buffer);
			}
		}

		static DirectoryEntry decode(ByteBuffer buffer) throws ProtocolException {
			return new DirectoryEntry(Wire.getString(buffer), NodeType.decode(buffer), buffer.getLong(), buffer.getLong(), Wire.getBoolean(buffer) ? Attributes.decode(buffer) : null);
		}
	}

	/* requests */

	public sealed interface Request {

		Opcode opcode();

		default void encode(ByteBuffer control) {
		}

		default ByteBuffer payload() {
			return EMPTY;
		}

		default Frame toFrame(long requestId) {
			return frame(Frame.Kind.REQUEST, opcode(), requestId, this::encode, payload());
		}
	}

	public record HelloRequest(int magic, int protocolVersion, byte[] token) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.HELLO;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putInt(magic).putShort((short) protocolVersion).put(token);
		}

		// what follows magic and version is not interpreted here, since a client speaking another version may lay it out differently
		static HelloRequest decode(ByteBuffer control) throws ProtocolException {
			return new HelloRequest(control.getInt(), Short.toUnsignedInt(control.getShort()), Wire.getBytes(control, control.remaining()));
		}
	}

	public record StatfsRequest() implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.STATFS;
		}
	}

	public record LookupRequest(long parentId, String name) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.LOOKUP;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(parentId);
			Wire.putString(control, name);
		}

		static LookupRequest decode(ByteBuffer control) throws ProtocolException {
			return new LookupRequest(control.getLong(), Wire.getString(control));
		}
	}

	public record ForgetRequest(long nodeId) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.FORGET;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId);
		}

		static ForgetRequest decode(ByteBuffer control) {
			return new ForgetRequest(control.getLong());
		}
	}

	public record GetattrRequest(long nodeId) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.GETATTR;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId);
		}

		static GetattrRequest decode(ByteBuffer control) {
			return new GetattrRequest(control.getLong());
		}
	}

	public record SetattrRequest(long nodeId, int valid, long size, int mode, Timestamp accessed, Timestamp modified) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.SETATTR;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId).put((byte) valid).putLong(size).putShort((short) mode);
			accessed.encode(control);
			modified.encode(control);
		}

		static SetattrRequest decode(ByteBuffer control) {
			return new SetattrRequest(control.getLong(), Byte.toUnsignedInt(control.get()), control.getLong(), Short.toUnsignedInt(control.getShort()), Timestamp.decode(control), Timestamp.decode(control));
		}
	}

	public record ReaddirRequest(long nodeId, long cookie, long verifier, boolean wantAttributes) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.READDIR;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId).putLong(cookie).putLong(verifier);
			Wire.putBoolean(control, wantAttributes);
		}

		static ReaddirRequest decode(ByteBuffer control) throws ProtocolException {
			return new ReaddirRequest(control.getLong(), control.getLong(), control.getLong(), Wire.getBoolean(control));
		}
	}

	public record CreateRequest(long parentId, String name, NodeType type, int mode) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.CREATE;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(parentId);
			Wire.putString(control, name);
			type.encode(control);
			control.putShort((short) mode);
		}

		static CreateRequest decode(ByteBuffer control) throws ProtocolException {
			return new CreateRequest(control.getLong(), Wire.getString(control), NodeType.decode(control), Short.toUnsignedInt(control.getShort()));
		}
	}

	public record RemoveRequest(long nodeId, long parentId) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.REMOVE;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId).putLong(parentId);
		}

		static RemoveRequest decode(ByteBuffer control) {
			return new RemoveRequest(control.getLong(), control.getLong());
		}
	}

	public record RenameRequest(long nodeId, long sourceParentId, long destinationParentId, String destinationName) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.RENAME;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId).putLong(sourceParentId).putLong(destinationParentId);
			Wire.putString(control, destinationName);
		}

		static RenameRequest decode(ByteBuffer control) throws ProtocolException {
			return new RenameRequest(control.getLong(), control.getLong(), control.getLong(), Wire.getString(control));
		}
	}

	public record OpenRequest(long nodeId, int modes) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.OPEN;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId).put((byte) modes);
		}

		static OpenRequest decode(ByteBuffer control) {
			return new OpenRequest(control.getLong(), Byte.toUnsignedInt(control.get()));
		}
	}

	public record CloseRequest(long nodeId, int keptModes) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.CLOSE;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId).put((byte) keptModes);
		}

		static CloseRequest decode(ByteBuffer control) {
			return new CloseRequest(control.getLong(), Byte.toUnsignedInt(control.get()));
		}
	}

	public record ReadRequest(long nodeId, long offset, int length) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.READ;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId).putLong(offset).putInt(length);
		}

		static ReadRequest decode(ByteBuffer control) {
			return new ReadRequest(control.getLong(), control.getLong(), control.getInt());
		}
	}

	public record WriteRequest(long nodeId, long offset, ByteBuffer data) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.WRITE;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId).putLong(offset);
		}

		@Override
		public ByteBuffer payload() {
			return data;
		}

		static WriteRequest decode(ByteBuffer control, ByteBuffer payload) {
			return new WriteRequest(control.getLong(), control.getLong(), payload);
		}
	}

	public record SyncRequest() implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.SYNC;
		}
	}

	public record ReadlinkRequest(long nodeId) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.READLINK;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(nodeId);
		}

		static ReadlinkRequest decode(ByteBuffer control) {
			return new ReadlinkRequest(control.getLong());
		}
	}

	public record SymlinkRequest(long parentId, String name, String target) implements Request {

		@Override
		public Opcode opcode() {
			return Opcode.SYMLINK;
		}

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(parentId);
			Wire.putString(control, name);
			Wire.putString(control, target);
		}

		static SymlinkRequest decode(ByteBuffer control) throws ProtocolException {
			return new SymlinkRequest(control.getLong(), Wire.getString(control), Wire.getString(control));
		}
	}

	/* responses */

	public sealed interface Response {

		default int status() {
			return 0;
		}

		default void encode(ByteBuffer control) {
		}

		default ByteBuffer payload() {
			return EMPTY;
		}

		default Frame toFrame(Opcode opcode, long requestId) {
			return frame(Frame.Kind.RESPONSE, opcode, requestId, control -> {
				control.putInt(status());
				encode(control);
			}, payload());
		}
	}

	/**
	 * The response to any request that did not succeed.
	 *
	 * @param status A macOS errno value or {@link #STATUS_INVALID_COOKIE}
	 */
	public record Failure(int status) implements Response {

		public Failure {
			if (status == 0) {
				throw new IllegalArgumentException("A failure needs a non-zero status");
			}
		}
	}

	public record HelloResponse() implements Response {
	}

	public record StatfsResponse(long totalBytes, long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(totalBytes).putLong(usableBytes);
		}

		static StatfsResponse decode(ByteBuffer control) {
			return new StatfsResponse(control.getLong(), control.getLong());
		}
	}

	public record LookupResponse(Attributes attributes, String name) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			attributes.encode(control);
			Wire.putString(control, name);
		}

		static LookupResponse decode(ByteBuffer control) throws ProtocolException {
			return new LookupResponse(Attributes.decode(control), Wire.getString(control));
		}
	}

	public record ForgetResponse(long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(usableBytes);
		}

		static ForgetResponse decode(ByteBuffer control) {
			return new ForgetResponse(control.getLong());
		}
	}

	public record GetattrResponse(Attributes attributes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			attributes.encode(control);
		}

		static GetattrResponse decode(ByteBuffer control) throws ProtocolException {
			return new GetattrResponse(Attributes.decode(control));
		}
	}

	public record SetattrResponse(int applied, Attributes attributes, long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			control.put((byte) applied);
			attributes.encode(control);
			control.putLong(usableBytes);
		}

		static SetattrResponse decode(ByteBuffer control) throws ProtocolException {
			return new SetattrResponse(Byte.toUnsignedInt(control.get()), Attributes.decode(control), control.getLong());
		}
	}

	public record ReaddirResponse(long verifier, boolean more, List<DirectoryEntry> entries) implements Response {

		/**
		 * The bytes of a control section that entries can occupy.
		 */
		public static final int ENTRIES_CAPACITY = FrameCodec.MAX_CONTROL_LENGTH - (4 + 8 + 1 + 2);

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(verifier);
			Wire.putBoolean(control, more);
			control.putShort((short) entries.size());
			entries.forEach(entry -> entry.encode(control));
		}

		static ReaddirResponse decode(ByteBuffer control) throws ProtocolException {
			long verifier = control.getLong();
			boolean more = Wire.getBoolean(control);
			int count = Short.toUnsignedInt(control.getShort());
			List<DirectoryEntry> entries = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				entries.add(DirectoryEntry.decode(control));
			}
			return new ReaddirResponse(verifier, more, entries);
		}
	}

	public record CreateResponse(Attributes attributes, String name, Attributes directoryAttributes, long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			attributes.encode(control);
			Wire.putString(control, name);
			directoryAttributes.encode(control);
			control.putLong(usableBytes);
		}

		static CreateResponse decode(ByteBuffer control) throws ProtocolException {
			return new CreateResponse(Attributes.decode(control), Wire.getString(control), Attributes.decode(control), control.getLong());
		}
	}

	public record RemoveResponse(Attributes attributes, Attributes directoryAttributes, long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			attributes.encode(control);
			directoryAttributes.encode(control);
			control.putLong(usableBytes);
		}

		static RemoveResponse decode(ByteBuffer control) throws ProtocolException {
			return new RemoveResponse(Attributes.decode(control), Attributes.decode(control), control.getLong());
		}
	}

	public record RenameResponse(String name, Attributes attributes, Attributes sourceDirectoryAttributes, Attributes destinationDirectoryAttributes, @Nullable Attributes replacedAttributes, long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			Wire.putString(control, name);
			attributes.encode(control);
			sourceDirectoryAttributes.encode(control);
			destinationDirectoryAttributes.encode(control);
			Wire.putBoolean(control, replacedAttributes != null);
			if (replacedAttributes != null) {
				replacedAttributes.encode(control);
			}
			control.putLong(usableBytes);
		}

		static RenameResponse decode(ByteBuffer control) throws ProtocolException {
			return new RenameResponse(Wire.getString(control), Attributes.decode(control), Attributes.decode(control), Attributes.decode(control), Wire.getBoolean(control) ? Attributes.decode(control) : null, control.getLong());
		}
	}

	public record OpenResponse() implements Response {
	}

	public record CloseResponse(long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(usableBytes);
		}

		static CloseResponse decode(ByteBuffer control) {
			return new CloseResponse(control.getLong());
		}
	}

	public record ReadResponse(Attributes attributes, ByteBuffer data) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			attributes.encode(control);
		}

		@Override
		public ByteBuffer payload() {
			return data;
		}

		static ReadResponse decode(ByteBuffer control, ByteBuffer payload) throws ProtocolException {
			return new ReadResponse(Attributes.decode(control), payload);
		}
	}

	public record WriteResponse(int written, Attributes attributes, long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			control.putInt(written);
			attributes.encode(control);
			control.putLong(usableBytes);
		}

		static WriteResponse decode(ByteBuffer control) throws ProtocolException {
			return new WriteResponse(control.getInt(), Attributes.decode(control), control.getLong());
		}
	}

	public record SyncResponse(long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			control.putLong(usableBytes);
		}

		static SyncResponse decode(ByteBuffer control) {
			return new SyncResponse(control.getLong());
		}
	}

	public record ReadlinkResponse(Attributes attributes, String target) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			attributes.encode(control);
			Wire.putString(control, target);
		}

		static ReadlinkResponse decode(ByteBuffer control) throws ProtocolException {
			return new ReadlinkResponse(Attributes.decode(control), Wire.getString(control));
		}
	}

	public record SymlinkResponse(Attributes attributes, String name, Attributes directoryAttributes, long usableBytes) implements Response {

		@Override
		public void encode(ByteBuffer control) {
			attributes.encode(control);
			Wire.putString(control, name);
			directoryAttributes.encode(control);
			control.putLong(usableBytes);
		}

		static SymlinkResponse decode(ByteBuffer control) throws ProtocolException {
			return new SymlinkResponse(Attributes.decode(control), Wire.getString(control), Attributes.decode(control), control.getLong());
		}
	}
}
