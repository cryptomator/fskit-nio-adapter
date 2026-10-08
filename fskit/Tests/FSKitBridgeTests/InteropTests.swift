import Foundation
import Testing
@testable import FSKitBridge

/// Drives the Java server with the Swift client. `scripts/interop-test.sh` starts the server and passes the directory that holds its manifest.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["FSKIT_INTEROP_RENDEZVOUS_DIR"] != nil))
struct InteropTests {
	private static let root = Messages.rootNodeId

	// swiftlint:disable:next function_body_length
	@Test func clientAndServerAgreeOnAnOperationSequence() async throws {
		let rendezvousDir = try URL(fileURLWithPath: #require(ProcessInfo.processInfo.environment["FSKIT_INTEROP_RENDEZVOUS_DIR"]))
		let manifest = try Manifest.read(from: rendezvousDir)
		#expect(manifest.volumeName == "Interop ä")
		let client = try BridgeClient(manifest: manifest)
		defer { client.disconnect() }

		#expect(try await client.request(StatfsRequest()).totalBytes > 0)

		let directory = try await client.request(CreateRequest(parentId: Self.root, name: "dir", type: .directory, mode: 0o755))
		#expect(directory.attributes.type == .directory)
		#expect(directory.attributes.parentId == Self.root)
		#expect(directory.directoryAttributes.nodeId == Self.root)

		let created = try await client.request(CreateRequest(parentId: directory.attributes.nodeId, name: "a\u{0308}.txt", type: .file, mode: 0o640))
		let file = created.attributes.nodeId
		#expect(created.name == "\u{00e4}.txt")
		#expect(created.attributes.type == .file)
		#expect(created.attributes.mode == 0o640)
		#expect(created.attributes.size == 0)

		// larger than one payload, so the client has to split both the write and the read
		let content = Data(testContentOfLength: 3 * FrameCodec.maxPayloadLength + 4711)
		_ = try await client.request(OpenRequest(nodeId: file, modes: Messages.modeRead | Messages.modeWrite))
		let written = try await client.write(nodeId: file, offset: 0, data: content)
		#expect(Int(written.written) == content.count)
		#expect(Int(written.attributes.size) == content.count)
		let read = try await client.read(nodeId: file, offset: 0, length: content.count + 100)
		#expect(read.data == content)
		#expect(try await client.read(nodeId: file, offset: 5, length: 10).data == content.subdata(in: 5 ..< 15))

		let truncated = try await client.request(SetattrRequest(nodeId: file, valid: Messages.attributeSize | Messages.attributeModified, size: 3, mode: 0, accessed: Timestamp(seconds: 0, nanos: 0), modified: Timestamp(seconds: 1_700_000_000, nanos: 5)))
		#expect(truncated.applied == Messages.attributeSize | Messages.attributeModified)
		#expect(truncated.attributes.size == 3)
		#expect(truncated.attributes.modified.seconds == 1_700_000_000)
		#expect(try await client.request(CloseRequest(nodeId: file, keptModes: 0)).freeSpace.usableBytes > 0)

		let renamed = try await client.request(RenameRequest(nodeId: file, sourceParentId: directory.attributes.nodeId, sourceName: created.name, destinationParentId: Self.root, destinationName: "renamed.txt"))
		#expect(renamed.name == "renamed.txt")
		#expect(renamed.attributes.nodeId == file)
		#expect(renamed.attributes.parentId == Self.root)
		#expect(renamed.replacedAttributes == nil)
		#expect(try await client.request(LookupRequest(parentId: Self.root, name: "renamed.txt")).attributes.nodeId == file)
		#expect(try await client.request(GetattrRequest(nodeId: file)).attributes.size == 3)

		let names = try await client.request(ReaddirRequest(nodeId: Self.root, cookie: 0, verifier: 0, wantAttributes: false))
		#expect(names.verifier != 0)
		#expect(!names.more)
		#expect(names.entries.map(\.name).sorted() == [".", "..", "dir", "renamed.txt"])
		let withAttributes = try await client.request(ReaddirRequest(nodeId: Self.root, cookie: 0, verifier: 0, wantAttributes: true))
		#expect(withAttributes.entries.map(\.name).sorted() == ["dir", "renamed.txt"])
		#expect(withAttributes.entries.first { $0.name == "renamed.txt" }?.attributes?.size == 3)
		await #expect(throws: StatusError(status: Messages.statusInvalidCookie)) {
			try await client.request(ReaddirRequest(nodeId: Self.root, cookie: 1, verifier: names.verifier + 100, wantAttributes: false))
		}

		await #expect(throws: StatusError(status: ENOENT)) {
			try await client.request(LookupRequest(parentId: Self.root, name: "missing"))
		}

		let link = try await client.request(SymlinkRequest(parentId: Self.root, name: "link", target: "../outside/renamed.txt"))
		#expect(link.name == "link")
		#expect(link.attributes.type == .symlink)
		#expect(link.directoryAttributes.nodeId == Self.root)
		let target = try await client.request(ReadlinkRequest(nodeId: link.attributes.nodeId))
		#expect(target.target == "../outside/renamed.txt")
		#expect(target.attributes.nodeId == link.attributes.nodeId)
		_ = try await client.request(RemoveRequest(nodeId: link.attributes.nodeId, parentId: Self.root, name: "link"))

		// more entries than one enumeration packs, in a directory of their own
		let listing = try await client.request(CreateRequest(parentId: Self.root, name: "listing", type: .directory, mode: 0o755)).attributes.nodeId
		let children = try await (0 ..< 10).asyncMap { try await client.request(CreateRequest(parentId: listing, name: "\($0)", type: .directory, mode: 0o755)).attributes.nodeId }
		let listed = try await DirectoryLister(client: client).listAll(directory: listing, capacity: 4, counting: client)
		#expect(listed.names.sorted() == ([".", ".."] + (0 ..< 10).map { "\($0)" }).sorted())
		// the two calls that continue the first page are served from what the first call kept, and the last one finds the end
		#expect(listed.requestsPerCall == [1, 0, 0, 1])
		for (index, child) in children.enumerated() {
			_ = try await client.request(RemoveRequest(nodeId: child, parentId: listing, name: "\(index)"))
		}
		_ = try await client.request(RemoveRequest(nodeId: listing, parentId: Self.root, name: "listing"))

		try await concurrentRequestsAreEachAnswered(client)

		#expect(try await client.request(RemoveRequest(nodeId: file, parentId: Self.root, name: "renamed.txt")).attributes.size == 3)
		_ = try await client.request(RemoveRequest(nodeId: directory.attributes.nodeId, parentId: Self.root, name: "dir"))
		#expect(try await client.request(ReaddirRequest(nodeId: Self.root, cookie: 0, verifier: 0, wantAttributes: true)).entries.isEmpty)
		// created and looked up once each
		_ = try await client.request(ForgetRequest(nodeId: file, lookups: 2))
		_ = try await client.request(SyncRequest())
	}

	/// In a directory of its own, reads two files in one subdirectory while it creates and renames in another and lists the directory itself, all at once.
	private func concurrentRequestsAreEachAnswered(_ client: BridgeClient) async throws {
		let directory = try await client.request(CreateRequest(parentId: Self.root, name: "concurrent", type: .directory, mode: 0o755)).attributes.nodeId
		let reading = try await client.request(CreateRequest(parentId: directory, name: "reading", type: .directory, mode: 0o755)).attributes.nodeId
		let changing = try await client.request(CreateRequest(parentId: directory, name: "changing", type: .directory, mode: 0o755)).attributes.nodeId
		var files: [UInt64: (name: String, content: Data)] = [:]
		for index in 0 ..< 2 {
			let file = try await client.request(CreateRequest(parentId: reading, name: "\(index)", type: .file, mode: 0o644)).attributes.nodeId
			files[file] = ("\(index)", Data(testContentOfLength: FrameCodec.maxPayloadLength + 1000 * index))
			_ = try await client.write(nodeId: file, offset: 0, data: files[file]!.content)
		}

		try await withThrowingTaskGroup(of: Void.self) { group in
			for (file, (_, content)) in files {
				group.addTask {
					let read = try await client.read(nodeId: file, offset: 0, length: content.count + 1)
					#expect(read.data == content)
				}
			}
			group.addTask {
				let created = try await client.request(CreateRequest(parentId: changing, name: "created", type: .file, mode: 0o644))
				let renamed = try await client.request(RenameRequest(nodeId: created.attributes.nodeId, sourceParentId: changing, sourceName: "created", destinationParentId: changing, destinationName: "renamed"))
				#expect(renamed.name == "renamed")
				#expect(renamed.attributes.nodeId == created.attributes.nodeId)
				#expect(renamed.attributes.generation > created.attributes.generation)
			}
			group.addTask {
				let listing = try await client.request(ReaddirRequest(nodeId: directory, cookie: 0, verifier: 0, wantAttributes: true))
				#expect(listing.entries.map(\.name).sorted() == ["changing", "reading"])
			}
			try await group.waitForAll()
		}

		for (file, (name, _)) in files {
			_ = try await client.request(CloseRequest(nodeId: file, keptModes: 0))
			_ = try await client.request(RemoveRequest(nodeId: file, parentId: reading, name: name))
		}
		let renamed = try await client.request(LookupRequest(parentId: changing, name: "renamed")).attributes.nodeId
		_ = try await client.request(RemoveRequest(nodeId: renamed, parentId: changing, name: "renamed"))
		_ = try await client.request(RemoveRequest(nodeId: reading, parentId: directory, name: "reading"))
		_ = try await client.request(RemoveRequest(nodeId: changing, parentId: directory, name: "changing"))
		_ = try await client.request(RemoveRequest(nodeId: directory, parentId: Self.root, name: "concurrent"))
	}
}

extension Sequence {
	func asyncMap<T>(_ transform: (Element) async throws -> T) async rethrows -> [T] {
		var mapped: [T] = []
		for element in self {
			try await mapped.append(transform(element))
		}
		return mapped
	}
}
