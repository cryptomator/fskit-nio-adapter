package org.cryptomator.frontend.fskit.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class ManifestTest {

	private static byte[] bytes(ByteBuffer buffer) {
		byte[] bytes = new byte[buffer.remaining()];
		buffer.get(bytes);
		return bytes;
	}

	@Test
	@DisplayName("decodes the example manifest")
	public void testDecodeVector() throws IOException {
		Vector vector = Vector.load("manifest.txt");

		Manifest manifest = Manifest.decode(ByteBuffer.wrap(vector.bytes()));

		Assertions.assertEquals(vector.fields(), Vector.describe(manifest));
	}

	@Test
	@DisplayName("encodes the example manifest")
	public void testEncodeVector() {
		Vector vector = Vector.load("manifest.txt");
		byte[] token = new byte[32];
		for (int i = 0; i < token.length; i++) {
			token[i] = (byte) (0x20 + i);
		}

		Manifest manifest = new Manifest(1, 51234, token, "Vault ä");

		Assertions.assertArrayEquals(vector.bytes(), bytes(manifest.encode()));
	}

	@Test
	@DisplayName("writes a file named manifest that only its owner can access, reads it back and deletes it")
	public void testWriteReadAndDelete(@TempDir Path tmpDir) throws IOException {
		Manifest manifest = new Manifest(Messages.PROTOCOL_VERSION, 65535, new byte[32], "Müller's Vault");

		manifest.write(tmpDir);

		Assertions.assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tmpDir.resolve("manifest"))));
		Assertions.assertEquals(Vector.describe(manifest), Vector.describe(Manifest.read(tmpDir)));

		Manifest.delete(tmpDir);
		Manifest.delete(tmpDir);

		try (Stream<Path> files = Files.list(tmpDir)) {
			Assertions.assertEquals(List.of(), files.toList());
		}
	}

	@Test
	@DisplayName("rejects a different protocol version")
	public void testDecodeWrongVersion() {
		byte[] bytes = Vector.load("manifest.txt").bytes();
		bytes[1] = 2;

		Assertions.assertThrows(ProtocolException.class, () -> Manifest.decode(ByteBuffer.wrap(bytes)));
	}

	@Test
	@DisplayName("rejects a truncated manifest")
	public void testDecodeTruncated() {
		byte[] bytes = Vector.load("manifest.txt").bytes();

		for (int length = 0; length < bytes.length; length++) {
			byte[] truncated = Arrays.copyOf(bytes, length);
			Assertions.assertThrows(ProtocolException.class, () -> Manifest.decode(ByteBuffer.wrap(truncated)), "length " + length);
		}
	}

	@Test
	@DisplayName("rejects bytes after the manifest")
	public void testDecodeTrailingBytes() {
		byte[] bytes = Vector.load("manifest.txt").bytes();

		Assertions.assertThrows(ProtocolException.class, () -> Manifest.decode(ByteBuffer.wrap(Arrays.copyOf(bytes, bytes.length + 1))));
	}
}
