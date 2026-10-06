package org.cryptomator.frontend.fskit.protocol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Arrays;

public class FrameCodecTest {

	public static Frame read(byte[] bytes) throws IOException {
		return FrameCodec.read(Channels.newChannel(new ByteArrayInputStream(bytes)));
	}

	public static byte[] write(Frame frame) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FrameCodec.write(Channels.newChannel(out), frame);
		return out.toByteArray();
	}

	@Test
	@DisplayName("decodes the example frame")
	public void testReadVector() throws IOException {
		Vector vector = Vector.load("frame.txt");

		Frame frame = read(vector.bytes());

		Assertions.assertEquals(vector.fields(), Vector.describe(frame));
	}

	@Test
	@DisplayName("encodes the example frame")
	public void testWriteVector() throws IOException {
		Vector vector = Vector.load("frame.txt");
		Frame frame = new Frame(Frame.Kind.REQUEST, Opcode.GETATTR, 0x0102030405060708L, ByteBuffer.wrap(Vector.hex("aabbcc")), ByteBuffer.wrap(Vector.hex("ddeeff00")));

		Assertions.assertArrayEquals(vector.bytes(), write(frame));
	}

	@Test
	@DisplayName("reads consecutive frames from one channel")
	public void testReadConsecutiveFrames() throws IOException {
		byte[] one = Vector.load("frame.txt").bytes();
		byte[] two = Vector.load("hello-response.txt").bytes();
		ByteArrayOutputStream both = new ByteArrayOutputStream();
		both.write(one);
		both.write(two);
		var channel = Channels.newChannel(new ByteArrayInputStream(both.toByteArray()));

		Assertions.assertEquals(Opcode.GETATTR, FrameCodec.read(channel).opcode());
		Assertions.assertEquals(Opcode.HELLO, FrameCodec.read(channel).opcode());
		Assertions.assertThrows(EOFException.class, () -> FrameCodec.read(channel));
	}

	@ParameterizedTest(name = "after {0} bytes")
	@DisplayName("rejects a truncated frame")
	@ValueSource(ints = {0, 3, 4, 18, 19, 21, 25})
	public void testReadTruncated(int length) {
		byte[] truncated = Arrays.copyOf(Vector.load("frame.txt").bytes(), length);

		Assertions.assertThrows(EOFException.class, () -> read(truncated));
	}

	@ParameterizedTest(name = "{0}")
	@DisplayName("rejects a frame that violates the limits")
	@ValueSource(strings = { //
			"0000000e 00 0005 0000000000000001 00000000", // shorter than a header
			"00110010 00 0005 0000000000000001 00000000", // longer than control and payload limits allow
			"ffffffff 00 0005 0000000000000001 00000000", // length with the sign bit set
			"0000000f 00 0005 0000000000000001 00000001", // control exceeds the frame
			"0001000f 00 0005 0000000000000001 00010001", // control exceeds its limit
			"0000000f 00 0005 0000000000000001 ffffffff", // control length with the sign bit set
			"00100010 00 0005 0000000000000001 00000000"}) // payload exceeds its limit
	public void testReadViolatingLimits(String header) {
		Assertions.assertThrows(ProtocolException.class, () -> read(Vector.hex(header)));
	}

	@Test
	@DisplayName("accepts control and payload at their limits")
	public void testReadWriteAtLimits() throws IOException {
		Frame frame = new Frame(Frame.Kind.RESPONSE, Opcode.READ, 1, ByteBuffer.allocate(FrameCodec.MAX_CONTROL_LENGTH), ByteBuffer.allocate(FrameCodec.MAX_PAYLOAD_LENGTH));

		Assertions.assertEquals(frame, read(write(frame)));
	}

	@Test
	@DisplayName("rejects an unknown opcode")
	public void testReadUnknownOpcode() {
		Assertions.assertThrows(ProtocolException.class, () -> read(Vector.hex("0000000f 00 7fff 0000000000000001 00000000")));
	}

	@Test
	@DisplayName("rejects an unknown kind")
	public void testReadUnknownKind() {
		Assertions.assertThrows(ProtocolException.class, () -> read(Vector.hex("0000000f 02 0005 0000000000000001 00000000")));
	}

	@Test
	@DisplayName("refuses to write an oversized control section")
	public void testWriteOversizedControl() {
		Frame frame = new Frame(Frame.Kind.RESPONSE, Opcode.READDIR, 1, ByteBuffer.allocate(FrameCodec.MAX_CONTROL_LENGTH + 1), ByteBuffer.allocate(0));

		Assertions.assertThrows(ProtocolException.class, () -> write(frame));
	}

	@Test
	@DisplayName("refuses to write an oversized payload")
	public void testWriteOversizedPayload() {
		Frame frame = new Frame(Frame.Kind.REQUEST, Opcode.WRITE, 1, ByteBuffer.allocate(0), ByteBuffer.allocate(FrameCodec.MAX_PAYLOAD_LENGTH + 1));

		Assertions.assertThrows(ProtocolException.class, () -> write(frame));
	}
}
