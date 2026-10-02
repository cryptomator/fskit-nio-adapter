package org.cryptomator.frontend.fskit.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Primitives shared by {@link Messages} and {@link Manifest}.
 */
final class Wire {

	private static final int MAX_STRING_LENGTH = 0xFFFF;

	private Wire() {
	}

	/**
	 * The most bytes {@link #putString} writes for a string: its length and up to three bytes per {@code char}.
	 */
	static int maxEncodedLength(String string) {
		return 2 + 3 * string.length();
	}

	static void putString(ByteBuffer buffer, String string) {
		byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > MAX_STRING_LENGTH) {
			throw new IllegalArgumentException("String exceeds " + MAX_STRING_LENGTH + " bytes");
		}
		buffer.putShort((short) bytes.length).put(bytes);
	}

	static String getString(ByteBuffer buffer) throws ProtocolException {
		int length = getLength(buffer, Short.toUnsignedInt(buffer.getShort()));
		ByteBuffer bytes = buffer.slice(buffer.position(), length);
		buffer.position(buffer.position() + length);
		try {
			return StandardCharsets.UTF_8.newDecoder() //
					.onMalformedInput(CodingErrorAction.REPORT) //
					.onUnmappableCharacter(CodingErrorAction.REPORT) //
					.decode(bytes).toString();
		} catch (CharacterCodingException e) {
			throw new ProtocolException("String is not valid UTF-8", e);
		}
	}

	static void putBoolean(ByteBuffer buffer, boolean value) {
		buffer.put((byte) (value ? 1 : 0));
	}

	static boolean getBoolean(ByteBuffer buffer) throws ProtocolException {
		return switch (buffer.get()) {
			case 0 -> false;
			case 1 -> true;
			default -> throw new ProtocolException("Invalid boolean");
		};
	}

	static byte[] getBytes(ByteBuffer buffer, int length) throws ProtocolException {
		byte[] bytes = new byte[getLength(buffer, length)];
		buffer.get(bytes);
		return bytes;
	}

	private static int getLength(ByteBuffer buffer, int length) throws ProtocolException {
		if (length > buffer.remaining()) {
			throw new ProtocolException("Field of " + length + " bytes exceeds the remaining " + buffer.remaining());
		}
		return length;
	}
}
