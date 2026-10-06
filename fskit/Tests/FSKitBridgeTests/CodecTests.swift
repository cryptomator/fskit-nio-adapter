import Foundation
import Testing
@testable import FSKitBridge

struct FrameCodecTests {
	private static func decode(_ hex: String) throws -> Frame {
		try FrameCodec.decode(readingFrom: Data(hex: hex)!.reader())
	}

	@Test func decodesTheExampleFrame() throws {
		let vector = try Vector.load("frame.txt")

		let frame = try FrameCodec.decode(readingFrom: vector.bytes.reader())

		#expect(Vector.describe(frame) == vector.fields)
	}

	@Test func encodesTheExampleFrame() throws {
		let vector = try Vector.load("frame.txt")
		let frame = try Frame(kind: .request, opcode: .getattr, requestId: 0x0102_0304_0506_0708, control: #require(Data(hex: "aabbcc")), payload: #require(Data(hex: "ddeeff00")))

		#expect(try FrameCodec.encode(frame) == vector.bytes)
	}

	@Test(arguments: [0, 3, 4, 18, 19, 21, 25])
	func rejectsATruncatedFrame(length: Int) throws {
		let truncated = try Vector.load("frame.txt").bytes.prefix(length)

		#expect(throws: POSIXError.self) {
			try FrameCodec.decode(readingFrom: Data(truncated).reader())
		}
	}

	@Test(arguments: [
		"0000000e 00 0005 0000000000000001 00000000", // shorter than a header
		"00110010 00 0005 0000000000000001 00000000", // longer than control and payload limits allow
		"ffffffff 00 0005 0000000000000001 00000000", // length with the top bit set
		"0000000f 00 0005 0000000000000001 00000001", // control exceeds the frame
		"0001000f 00 0005 0000000000000001 00010001", // control exceeds its limit
		"0000000f 00 0005 0000000000000001 ffffffff", // control length with the top bit set
		"00100010 00 0005 0000000000000001 00000000", // payload exceeds its limit
		"0000000f 00 7fff 0000000000000001 00000000", // unknown opcode
		"0000000f 02 0005 0000000000000001 00000000" // unknown kind
	])
	func rejectsAnInvalidHeader(hex: String) {
		#expect(throws: ProtocolError.self) {
			try Self.decode(hex)
		}
	}

	@Test func acceptsControlAndPayloadAtTheirLimits() throws {
		let frame = Frame(kind: .response, opcode: .read, requestId: 1, control: Data(count: FrameCodec.maxControlLength), payload: Data(count: FrameCodec.maxPayloadLength))

		#expect(try FrameCodec.decode(readingFrom: FrameCodec.encode(frame).reader()) == frame)
	}

	@Test func refusesToEncodeAnOversizedFrame() {
		#expect(throws: ProtocolError.self) {
			try FrameCodec.encode(Frame(kind: .response, opcode: .readdir, requestId: 1, control: Data(count: FrameCodec.maxControlLength + 1), payload: Data()))
		}
		#expect(throws: ProtocolError.self) {
			try FrameCodec.encode(Frame(kind: .request, opcode: .write, requestId: 1, control: Data(), payload: Data(count: FrameCodec.maxPayloadLength + 1)))
		}
	}
}

struct MessagesTests {
	/// Decodes the message in a frame, lists its fields and encodes it again.
	// swiftlint:disable:next cyclomatic_complexity
	private static func roundTrip(_ frame: Frame) throws -> (fields: [String], frame: Frame) {
		switch frame.opcode {
		case .hello: try roundTrip(HelloRequest.self, frame)
		case .statfs: try roundTrip(StatfsRequest.self, frame)
		case .lookup: try roundTrip(LookupRequest.self, frame)
		case .forget: try roundTrip(ForgetRequest.self, frame)
		case .getattr: try roundTrip(GetattrRequest.self, frame)
		case .setattr: try roundTrip(SetattrRequest.self, frame)
		case .readdir: try roundTrip(ReaddirRequest.self, frame)
		case .create: try roundTrip(CreateRequest.self, frame)
		case .remove: try roundTrip(RemoveRequest.self, frame)
		case .rename: try roundTrip(RenameRequest.self, frame)
		case .open: try roundTrip(OpenRequest.self, frame)
		case .close: try roundTrip(CloseRequest.self, frame)
		case .read: try roundTrip(ReadRequest.self, frame)
		case .write: try roundTrip(WriteRequest.self, frame)
		case .sync: try roundTrip(SyncRequest.self, frame)
		case .readlink: try roundTrip(ReadlinkRequest.self, frame)
		case .symlink: try roundTrip(SymlinkRequest.self, frame)
		}
	}

	private static func roundTrip<R: Request>(_ type: R.Type, _ frame: Frame) throws -> (fields: [String], frame: Frame) {
		if frame.kind == .request {
			let request = try Messages.decodeRequest(type, from: frame)
			return (Vector.describe(request), Messages.frame(for: request, requestId: frame.requestId))
		}
		do {
			let response = try Messages.decodeResponse(R.Response.self, from: frame)
			return (["status = 0"] + Vector.describe(response), Messages.frame(for: response, opcode: frame.opcode, requestId: frame.requestId))
		} catch let failure as StatusError {
			return (["status = \(failure.status)"], Messages.frame(forFailure: failure.status, opcode: frame.opcode, requestId: frame.requestId))
		}
	}

	private static func frame(_ kind: Frame.Kind, _ opcode: Opcode, control: String, payload: String = "") -> Frame {
		Frame(kind: kind, opcode: opcode, requestId: 1, control: Data(hex: control)!, payload: Data(hex: payload)!)
	}

	@Test(arguments: try Vector.messages())
	func decodesEveryExample(vector: Vector) throws {
		let frame = try FrameCodec.decode(readingFrom: vector.bytes.reader())

		let fields = try ["kind = \(frame.kind)", "opcode = \(frame.opcode)", "requestId = \(frame.requestId)"] + Self.roundTrip(frame).fields

		#expect(fields == vector.fields)
	}

	@Test(arguments: try Vector.messages())
	func encodesEveryExample(vector: Vector) throws {
		let frame = try FrameCodec.decode(readingFrom: vector.bytes.reader())

		let encoded = try FrameCodec.encode(Self.roundTrip(frame).frame)

		#expect(encoded == vector.bytes)
	}

	@Test func theExamplesCoverEveryOpcode() throws {
		let names = try Vector.messages().map(\.name)

		for opcode in Opcode.allCases {
			#expect(names.contains("\(opcode)-request.txt"))
			#expect(names.contains { $0.hasPrefix("\(opcode)-response") })
		}
	}

	@Test func keepsTheValueForUnknownUsableBytes() throws {
		let frame = Messages.frame(for: ForgetResponse(usableBytes: Messages.unknownUsableBytes), opcode: .forget, requestId: 1)

		#expect(frame.control == Data(hex: "00000000 ffffffffffffffff"))
		#expect(try Messages.decodeResponse(ForgetResponse.self, from: frame).usableBytes == UInt64.max)
	}

	@Test func decodesAFailureWithoutInterpretingFurtherFields() {
		#expect(throws: StatusError(status: 2)) {
			try Messages.decodeResponse(LookupResponse.self, from: Self.frame(.response, .lookup, control: "00000002"))
		}
		#expect(throws: StatusError(status: Messages.statusInvalidCookie)) {
			try Messages.decodeResponse(ReaddirResponse.self, from: Self.frame(.response, .readdir, control: "ffffffff"))
		}
	}

	@Test func rejectsARequestAsAResponseAndViceVersa() {
		#expect(throws: ProtocolError.self) {
			try Messages.decodeResponse(SyncResponse.self, from: Self.frame(.request, .sync, control: ""))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(SyncRequest.self, from: Self.frame(.response, .sync, control: "00000000"))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(SyncRequest.self, from: Self.frame(.request, .statfs, control: ""))
		}
	}

	@Test func rejectsATruncatedControlSection() {
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(GetattrRequest.self, from: Self.frame(.request, .getattr, control: "00000000000000"))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(LookupRequest.self, from: Self.frame(.request, .lookup, control: "0000000000000002 0005 6162"))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeResponse(ForgetResponse.self, from: Self.frame(.response, .forget, control: "00000000 00000000"))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeResponse(ForgetResponse.self, from: Self.frame(.response, .forget, control: "000000"))
		}
	}

	@Test func rejectsBytesLeftOverInTheControlSection() {
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(GetattrRequest.self, from: Self.frame(.request, .getattr, control: "0000000000000040 00"))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(SyncRequest.self, from: Self.frame(.request, .sync, control: "00"))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeResponse(LookupResponse.self, from: Self.frame(.response, .lookup, control: "00000002 00"))
		}
	}

	@Test func rejectsAPayloadOnAMessageThatHasNone() {
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(GetattrRequest.self, from: Self.frame(.request, .getattr, control: "0000000000000040", payload: "00"))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeResponse(OpenResponse.self, from: Self.frame(.response, .open, control: "00000000", payload: "00"))
		}
		#expect(throws: ProtocolError.self) {
			try Messages.decodeResponse(ReadResponse.self, from: Self.frame(.response, .read, control: "00000005", payload: "00"))
		}
	}

	@Test func rejectsInvalidFieldValues() {
		// node type 4
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(CreateRequest.self, from: Self.frame(.request, .create, control: "0000000000000002 0001 61 04 01a4"))
		}
		// boolean 2
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(ReaddirRequest.self, from: Self.frame(.request, .readdir, control: "0000000000000002 0000000000000000 0000000000000000 02"))
		}
		// name that is not UTF-8
		#expect(throws: ProtocolError.self) {
			try Messages.decodeRequest(LookupRequest.self, from: Self.frame(.request, .lookup, control: "0000000000000002 0002 c328"))
		}
	}
}

struct ManifestTests {
	@Test func decodesTheExampleManifest() throws {
		let vector = try Vector.load("manifest.txt")

		let manifest = try Manifest(decoding: vector.bytes)

		#expect(Vector.describe(manifest) == vector.fields)
	}

	@Test func encodesTheExampleManifest() throws {
		let vector = try Vector.load("manifest.txt")

		let manifest = Manifest(protocolVersion: 1, port: 51234, token: Data((0 ..< 32).map { UInt8(0x20 + $0) }), volumeName: "Vault ä")

		#expect(manifest.encode() == vector.bytes)
	}

	@Test func readsTheManifestFromADirectory() throws {
		let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
		try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
		defer { try? FileManager.default.removeItem(at: directory) }
		let manifest = Manifest(protocolVersion: Messages.protocolVersion, port: 65535, token: Data(count: 32), volumeName: "Müller's Vault")
		try manifest.encode().write(to: directory.appendingPathComponent(Manifest.fileName))

		#expect(try Manifest.read(from: directory) == manifest)
	}

	@Test func rejectsADifferentProtocolVersion() throws {
		var bytes = try Vector.load("manifest.txt").bytes
		bytes[1] = 2

		#expect(throws: ProtocolError.self) {
			try Manifest(decoding: bytes)
		}
	}

	@Test func rejectsATruncatedManifest() throws {
		let bytes = try Vector.load("manifest.txt").bytes

		for length in 0 ..< bytes.count {
			#expect(throws: ProtocolError.self, "length \(length)") {
				try Manifest(decoding: Data(bytes.prefix(length)))
			}
		}
	}

	@Test func rejectsBytesAfterTheManifest() throws {
		let bytes = try Vector.load("manifest.txt").bytes

		#expect(throws: ProtocolError.self) {
			try Manifest(decoding: bytes + [0])
		}
	}
}
