import Foundation
import Testing
@testable import FSKitBridge

/// Drives the Java server with the Swift client. `scripts/interop-test.sh` starts the server and passes the directory that holds its manifest.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["FSKIT_INTEROP_RENDEZVOUS_DIR"] != nil))
struct InteropTests {
	private static let root = Messages.rootNodeId

	// swiftlint:disable:next function_body_length
	@Test func clientAndServerAgreeOnAnOperationSequence() throws {
		let rendezvousDir = try URL(fileURLWithPath: #require(ProcessInfo.processInfo.environment["FSKIT_INTEROP_RENDEZVOUS_DIR"]))
		let manifest = try Manifest.read(from: rendezvousDir)
		#expect(manifest.volumeName == "Interop ä")
		let client = try BridgeClient(manifest: manifest)
		defer { client.disconnect() }

		#expect(try client.request(StatfsRequest()).totalBytes > 0)

		let directory = try client.request(CreateRequest(parentId: Self.root, name: "dir", type: .directory, mode: 0o755))
		#expect(directory.attributes.type == .directory)
		#expect(directory.attributes.parentId == Self.root)
		#expect(directory.directoryAttributes.nodeId == Self.root)

		let created = try client.request(CreateRequest(parentId: directory.attributes.nodeId, name: "a\u{0308}.txt", type: .file, mode: 0o640))
		let file = created.attributes.nodeId
		#expect(created.name == "\u{00e4}.txt")
		#expect(created.attributes.type == .file)
		#expect(created.attributes.mode == 0o640)
		#expect(created.attributes.size == 0)

		// larger than one payload, so the client has to split both the write and the read
		let content = Data(testContentOfLength: 3 * FrameCodec.maxPayloadLength + 4711)
		_ = try client.request(OpenRequest(nodeId: file, modes: Messages.modeRead | Messages.modeWrite))
		let written = try client.write(nodeId: file, offset: 0, data: content)
		#expect(Int(written.written) == content.count)
		#expect(Int(written.attributes.size) == content.count)
		let read = try client.read(nodeId: file, offset: 0, length: content.count + 100)
		#expect(read.data == content)
		#expect(try client.read(nodeId: file, offset: 5, length: 10).data == content.subdata(in: 5 ..< 15))

		let truncated = try client.request(SetattrRequest(nodeId: file, valid: Messages.attributeSize | Messages.attributeModified, size: 3, mode: 0, accessed: Timestamp(seconds: 0, nanos: 0), modified: Timestamp(seconds: 1_700_000_000, nanos: 5)))
		#expect(truncated.applied == Messages.attributeSize | Messages.attributeModified)
		#expect(truncated.attributes.size == 3)
		#expect(truncated.attributes.modified.seconds == 1_700_000_000)
		#expect(try client.request(CloseRequest(nodeId: file, keptModes: 0)).usableBytes > 0)

		let renamed = try client.request(RenameRequest(nodeId: file, sourceParentId: directory.attributes.nodeId, destinationParentId: Self.root, destinationName: "renamed.txt"))
		#expect(renamed.name == "renamed.txt")
		#expect(renamed.attributes.nodeId == file)
		#expect(renamed.attributes.parentId == Self.root)
		#expect(renamed.replacedAttributes == nil)
		#expect(try client.request(LookupRequest(parentId: Self.root, name: "renamed.txt")).attributes.nodeId == file)
		#expect(try client.request(GetattrRequest(nodeId: file)).attributes.size == 3)

		let names = try client.request(ReaddirRequest(nodeId: Self.root, cookie: 0, verifier: 0, wantAttributes: false))
		#expect(names.verifier != 0)
		#expect(!names.more)
		#expect(names.entries.map(\.name).sorted() == [".", "..", "dir", "renamed.txt"])
		let withAttributes = try client.request(ReaddirRequest(nodeId: Self.root, cookie: 0, verifier: 0, wantAttributes: true))
		#expect(withAttributes.entries.map(\.name).sorted() == ["dir", "renamed.txt"])
		#expect(withAttributes.entries.first { $0.name == "renamed.txt" }?.attributes?.size == 3)
		#expect(throws: StatusError(status: Messages.statusInvalidCookie)) {
			try client.request(ReaddirRequest(nodeId: Self.root, cookie: 1, verifier: names.verifier + 100, wantAttributes: false))
		}

		#expect(throws: StatusError(status: ENOENT)) {
			try client.request(LookupRequest(parentId: Self.root, name: "missing"))
		}

		let link = try client.request(SymlinkRequest(parentId: Self.root, name: "link", target: "../outside/renamed.txt"))
		#expect(link.name == "link")
		#expect(link.attributes.type == .symlink)
		#expect(link.directoryAttributes.nodeId == Self.root)
		let target = try client.request(ReadlinkRequest(nodeId: link.attributes.nodeId))
		#expect(target.target == "../outside/renamed.txt")
		#expect(target.attributes.nodeId == link.attributes.nodeId)
		_ = try client.request(RemoveRequest(nodeId: link.attributes.nodeId, parentId: Self.root))

		#expect(try client.request(RemoveRequest(nodeId: file, parentId: Self.root)).attributes.size == 3)
		_ = try client.request(RemoveRequest(nodeId: directory.attributes.nodeId, parentId: Self.root))
		#expect(try client.request(ReaddirRequest(nodeId: Self.root, cookie: 0, verifier: 0, wantAttributes: true)).entries.isEmpty)
		_ = try client.request(ForgetRequest(nodeId: file))
		_ = try client.request(SyncRequest())
	}
}
