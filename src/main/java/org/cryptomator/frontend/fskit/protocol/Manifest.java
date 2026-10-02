package org.cryptomator.frontend.fskit.protocol;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Tells the extension where to find the server of a mount.
 */
public record Manifest(int protocolVersion, int port, byte[] token, String volumeName) {

	private static final String FILE_NAME = "manifest";

	public Manifest {
		if (token.length != Messages.TOKEN_LENGTH) {
			throw new IllegalArgumentException("Token must have " + Messages.TOKEN_LENGTH + " bytes");
		}
	}

	public ByteBuffer encode() {
		ByteBuffer buffer = ByteBuffer.allocate(2 + 2 + Messages.TOKEN_LENGTH + Wire.maxEncodedLength(volumeName));
		buffer.putShort((short) protocolVersion).putShort((short) port).put(token);
		Wire.putString(buffer, volumeName);
		return buffer.flip();
	}

	public static Manifest decode(ByteBuffer buffer) throws ProtocolException {
		try {
			int protocolVersion = Short.toUnsignedInt(buffer.getShort());
			if (protocolVersion != Messages.PROTOCOL_VERSION) {
				throw new ProtocolException("Unsupported protocol version " + protocolVersion);
			}
			Manifest manifest = new Manifest(protocolVersion, Short.toUnsignedInt(buffer.getShort()), Wire.getBytes(buffer, Messages.TOKEN_LENGTH), Wire.getString(buffer));
			if (buffer.hasRemaining()) {
				throw new ProtocolException(buffer.remaining() + " bytes left over in manifest");
			}
			return manifest;
		} catch (BufferUnderflowException e) {
			throw new ProtocolException("Truncated manifest", e);
		}
	}

	/**
	 * Writes this manifest to a new file in {@code directory} that only its owner can access.
	 */
	public void write(Path directory) throws IOException {
		var ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
		try (FileChannel channel = FileChannel.open(directory.resolve(FILE_NAME), Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), ownerOnly)) {
			ByteBuffer buffer = encode();
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
		}
	}

	public static Manifest read(Path directory) throws IOException {
		return decode(ByteBuffer.wrap(Files.readAllBytes(directory.resolve(FILE_NAME))));
	}

	public static void delete(Path directory) throws IOException {
		Files.deleteIfExists(directory.resolve(FILE_NAME));
	}
}
