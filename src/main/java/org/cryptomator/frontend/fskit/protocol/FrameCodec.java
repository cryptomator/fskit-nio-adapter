package org.cryptomator.frontend.fskit.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

public final class FrameCodec {

	public static final int MAX_CONTROL_LENGTH = 64 * 1024;
	public static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;
	private static final int HEADER_LENGTH = 1 + 2 + 8 + 4; // kind, opcode, request id, control length

	private FrameCodec() {
	}

	public static void write(WritableByteChannel channel, Frame frame) throws IOException {
		int controlLength = frame.control().remaining();
		int payloadLength = frame.payload().remaining();
		if (controlLength > MAX_CONTROL_LENGTH || payloadLength > MAX_PAYLOAD_LENGTH) {
			throw new ProtocolException("Frame exceeds limits: control " + controlLength + ", payload " + payloadLength);
		}
		ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + HEADER_LENGTH + controlLength + payloadLength);
		buffer.putInt(HEADER_LENGTH + controlLength + payloadLength);
		buffer.put((byte) frame.kind().wireValue());
		buffer.putShort((short) frame.opcode().wireValue());
		buffer.putLong(frame.requestId());
		buffer.putInt(controlLength);
		buffer.put(frame.control().duplicate());
		buffer.put(frame.payload().duplicate());
		buffer.flip();
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	/**
	 * Reads one frame.
	 *
	 * @throws EOFException      If the channel ends before the frame is complete
	 * @throws ProtocolException If the frame violates the limits or names an unknown kind or opcode
	 */
	public static Frame read(ReadableByteChannel channel) throws IOException {
		int length = readFully(channel, Integer.BYTES).getInt();
		if (length < HEADER_LENGTH || length > HEADER_LENGTH + MAX_CONTROL_LENGTH + MAX_PAYLOAD_LENGTH) {
			throw new ProtocolException("Invalid frame length " + Integer.toUnsignedString(length));
		}
		ByteBuffer header = readFully(channel, HEADER_LENGTH);
		Frame.Kind kind = Frame.Kind.of(Byte.toUnsignedInt(header.get()));
		Opcode opcode = Opcode.of(Short.toUnsignedInt(header.getShort()));
		long requestId = header.getLong();
		int controlLength = header.getInt();
		int payloadLength = length - HEADER_LENGTH - controlLength;
		if (controlLength < 0 || controlLength > MAX_CONTROL_LENGTH || payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
			throw new ProtocolException("Invalid control length " + Integer.toUnsignedString(controlLength) + " in frame of length " + length);
		}
		ByteBuffer control = readFully(channel, controlLength);
		ByteBuffer payload = readFully(channel, payloadLength);
		return new Frame(kind, opcode, requestId, control, payload);
	}

	private static ByteBuffer readFully(ReadableByteChannel channel, int length) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(length);
		while (buffer.hasRemaining()) {
			if (channel.read(buffer) < 0) {
				throw new EOFException();
			}
		}
		return buffer.flip();
	}
}
