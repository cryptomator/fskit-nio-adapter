package org.cryptomator.frontend.fskit.protocol;

import org.cryptomator.frontend.fskit.protocol.Messages.Failure;
import org.cryptomator.frontend.fskit.protocol.Messages.ForgetResponse;
import org.cryptomator.frontend.fskit.protocol.Messages.LookupRequest;
import org.cryptomator.frontend.fskit.protocol.Messages.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class MessagesTest {

	static Stream<Vector> vectors() throws IOException {
		return Vector.messages();
	}

	private static Record decode(Frame frame) throws ProtocolException {
		return (Record) (frame.kind() == Frame.Kind.REQUEST ? Messages.decodeRequest(frame) : Messages.decodeResponse(frame));
	}

	private static Frame frame(Frame.Kind kind, Opcode opcode, String controlHex, String payloadHex) {
		return new Frame(kind, opcode, 1, ByteBuffer.wrap(Vector.hex(controlHex)), ByteBuffer.wrap(Vector.hex(payloadHex)));
	}

	@ParameterizedTest(name = "{0}")
	@DisplayName("decodes every example")
	@MethodSource("vectors")
	public void testDecodeVector(Vector vector) throws IOException {
		Frame frame = FrameCodecTest.read(vector.bytes());

		Record message = decode(frame);

		List<String> fields = new ArrayList<>();
		fields.add("kind = " + frame.kind().name().toLowerCase(Locale.ROOT));
		fields.add("opcode = " + frame.opcode().name().toLowerCase(Locale.ROOT));
		fields.add("requestId = " + frame.requestId());
		if (message instanceof Response response) {
			fields.add("status = " + response.status());
		}
		if (!(message instanceof Failure)) {
			fields.addAll(Vector.describe(message));
		}
		Assertions.assertEquals(vector.fields(), fields);
	}

	@ParameterizedTest(name = "{0}")
	@DisplayName("encodes every example")
	@MethodSource("vectors")
	public void testEncodeVector(Vector vector) throws IOException {
		Frame frame = FrameCodecTest.read(vector.bytes());

		Frame encoded = switch (decode(frame)) {
			case Messages.Request request -> request.toFrame(frame.requestId());
			case Messages.Response response -> response.toFrame(frame.opcode(), frame.requestId());
			default -> throw new IllegalStateException();
		};

		Assertions.assertArrayEquals(vector.bytes(), FrameCodecTest.write(encoded));
	}

	@Test
	@DisplayName("the request examples cover every opcode")
	public void testVectorsCoverAllRequests() throws IOException {
		List<String> names = vectors().map(Vector::name).toList();

		for (Opcode opcode : Opcode.values()) {
			String name = opcode.name().toLowerCase(Locale.ROOT);
			Assertions.assertTrue(names.contains(name + "-request.txt"), name + " request");
			Assertions.assertTrue(names.stream().anyMatch(n -> n.startsWith(name + "-response")), name + " response");
		}
	}

	@Test
	@DisplayName("keeps the value for unknown usable bytes")
	public void testUnknownUsableBytes() throws ProtocolException {
		Frame frame = new ForgetResponse(Messages.UNKNOWN_USABLE_BYTES).toFrame(Opcode.FORGET, 1);

		Assertions.assertEquals("00000000ffffffffffffffff", HexFormat.of().formatHex(frame.control().array(), 0, frame.control().remaining()));
		Assertions.assertEquals(new ForgetResponse(Messages.UNKNOWN_USABLE_BYTES), Messages.decodeResponse(frame));
	}

	@Test
	@DisplayName("decodes a failure without interpreting further fields")
	public void testDecodeFailure() throws ProtocolException {
		Assertions.assertEquals(new Failure(2), Messages.decodeResponse(frame(Frame.Kind.RESPONSE, Opcode.LOOKUP, "00000002", "")));
		Assertions.assertEquals(new Failure(Messages.STATUS_INVALID_COOKIE), Messages.decodeResponse(frame(Frame.Kind.RESPONSE, Opcode.READDIR, "ffffffff", "")));
	}

	@Test
	@DisplayName("rejects a request as a response and vice versa")
	public void testDecodeWrongKind() {
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeResponse(frame(Frame.Kind.REQUEST, Opcode.SYNC, "", "")));
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.RESPONSE, Opcode.SYNC, "00000000", "")));
	}

	@Test
	@DisplayName("rejects a truncated control section")
	public void testDecodeTruncated() {
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.REQUEST, Opcode.GETATTR, "00000000000000", "")));
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.REQUEST, Opcode.LOOKUP, "0000000000000002 0005 6162", "")));
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeResponse(frame(Frame.Kind.RESPONSE, Opcode.FORGET, "00000000 00000000", "")));
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeResponse(frame(Frame.Kind.RESPONSE, Opcode.FORGET, "000000", "")));
	}

	@Test
	@DisplayName("rejects bytes left over in the control section")
	public void testDecodeTrailingBytes() {
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.REQUEST, Opcode.GETATTR, "0000000000000040 00", "")));
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.REQUEST, Opcode.SYNC, "00", "")));
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeResponse(frame(Frame.Kind.RESPONSE, Opcode.LOOKUP, "00000002 00", "")));
	}

	@Test
	@DisplayName("rejects a payload on a message that has none")
	public void testDecodeUnexpectedPayload() {
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.REQUEST, Opcode.GETATTR, "0000000000000040", "00")));
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeResponse(frame(Frame.Kind.RESPONSE, Opcode.OPEN, "00000000", "00")));
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeResponse(frame(Frame.Kind.RESPONSE, Opcode.READ, "00000005", "00")));
	}

	@Test
	@DisplayName("rejects invalid field values")
	public void testDecodeInvalidValues() {
		// node type 4
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.REQUEST, Opcode.CREATE, "0000000000000002 0001 61 04 01a4", "")));
		// boolean 2
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.REQUEST, Opcode.READDIR, "0000000000000002 0000000000000000 0000000000000000 02", "")));
		// name that is not UTF-8
		Assertions.assertThrows(ProtocolException.class, () -> Messages.decodeRequest(frame(Frame.Kind.REQUEST, Opcode.LOOKUP, "0000000000000002 0002 c328", "")));
	}

	@Test
	@DisplayName("refuses to encode a name longer than a string can hold")
	public void testEncodeOversizedName() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new LookupRequest(2, "a".repeat(0x10000)).toFrame(1));
	}
}
