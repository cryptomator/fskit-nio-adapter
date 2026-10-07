import Foundation
import os
import Testing
@testable import FSKitBridge

/// The entries one call packs, and the verifier it returns for FSKit.
struct Listed {
	var entries: [DirectoryEntry]
	var verifier: UInt64

	var names: [String] {
		entries.map(\.name)
	}
}

extension DirectoryLister {
	/// Lists as FSKit does, with a packer that takes `capacity` entries and refuses the next.
	func list(directory: UInt64, cookie: UInt64, verifier: UInt64, wantAttributes: Bool = false, capacity: Int) throws -> Listed {
		var entries: [DirectoryEntry] = []
		let returned = try list(directory: directory, cookie: cookie, verifier: verifier, wantAttributes: wantAttributes) { entry in
			guard entries.count < capacity else {
				return false
			}
			entries.append(entry)
			return true
		}
		return Listed(entries: entries, verifier: returned)
	}

	/// Lists a whole directory: each call continues after the last entry packed, with the verifier the call before returned, until a call packs nothing.
	///
	/// - Returns: The names packed over all calls, and the requests `client` sent for each call.
	func listAll(directory: UInt64, capacity: Int, counting client: BridgeClient) throws -> (names: [String], requestsPerCall: [UInt64]) {
		var names: [String] = []
		var requestsPerCall: [UInt64] = []
		var cookie: UInt64 = 0
		var verifier: UInt64 = 0
		// bounded, so that a lister that serves entries again fails the test instead of hanging it
		for _ in 0 ..< 10 {
			let requestsBefore = client.nextRequestId
			let listed = try list(directory: directory, cookie: cookie, verifier: verifier, capacity: capacity)
			requestsPerCall.append(client.nextRequestId - requestsBefore)
			guard let last = listed.entries.last else {
				break
			}
			names += listed.names
			cookie = last.nextCookie
			verifier = listed.verifier
		}
		return (names, requestsPerCall)
	}
}

struct DirectoryListerTests {
	private static let directory: UInt64 = 64
	private static let verifier: UInt64 = 7

	/// A server whose directory holds `count` entries named by their index and answers a `READDIR` with at most `pageSize` of them. It records every `READDIR` and answers `GETATTR` as well.
	private static func server(count: Int, pageSize: Int, requests: OSAllocatedUnfairLock<[ReaddirRequest]>) throws -> ScriptedServer {
		try ScriptedServer { frame in
			if frame.opcode == .getattr {
				let time = Timestamp(seconds: 0, nanos: 0)
				return Messages.frame(for: GetattrResponse(attributes: Attributes(type: .directory, mode: 0o755, size: 0, nodeId: directory, parentId: Messages.rootNodeId, modified: time, accessed: time, created: time)), opcode: .getattr, requestId: frame.requestId)
			}
			let request = try Messages.decodeRequest(ReaddirRequest.self, from: frame)
			requests.withLock { $0.append(request) }
			let start = min(Int(request.cookie), count)
			let end = min(start + pageSize, count)
			let entries = (start ..< end).map { DirectoryEntry(name: "\($0)", type: .file, nodeId: 100 + UInt64($0), nextCookie: UInt64($0 + 1), attributes: nil) }
			return Messages.frame(for: ReaddirResponse(verifier: verifier, more: end < count, entries: entries), opcode: .readdir, requestId: frame.requestId)
		}
	}

	private static func names(_ range: Range<Int>) -> [String] {
		range.map { "\($0)" }
	}

	@Test func continuingAtTheRefusedEntrySendsNoRequest() throws {
		let requests = OSAllocatedUnfairLock(initialState: [ReaddirRequest]())
		let server = try Self.server(count: 12, pageSize: 8, requests: requests)
		let client = try BridgeClient(manifest: server.manifest)
		let lister = DirectoryLister(client: client)

		let first = try lister.list(directory: Self.directory, cookie: 0, verifier: 0, capacity: 3)
		let second = try lister.list(directory: Self.directory, cookie: 3, verifier: first.verifier, capacity: 3)

		#expect(first.names == Self.names(0 ..< 3))
		#expect(second.names == Self.names(3 ..< 6))
		#expect(requests.withLock { $0.map(\.cookie) } == [0])
	}

	/// FSKit does so in an enumeration without attributes: it continues at an entry its packer already took, not at the one it refused.
	@Test func continuingAtAnEntryThePackerTookSendsNoRequest() throws {
		let requests = OSAllocatedUnfairLock(initialState: [ReaddirRequest]())
		let server = try Self.server(count: 12, pageSize: 8, requests: requests)
		let client = try BridgeClient(manifest: server.manifest)
		let lister = DirectoryLister(client: client)

		let first = try lister.list(directory: Self.directory, cookie: 0, verifier: 0, capacity: 3)
		let second = try lister.list(directory: Self.directory, cookie: 2, verifier: first.verifier, capacity: 3)
		let third = try lister.list(directory: Self.directory, cookie: 2, verifier: second.verifier, capacity: 2)

		#expect(second.names == Self.names(2 ..< 5))
		#expect(third.names == Self.names(2 ..< 4))
		#expect(requests.withLock { $0.map(\.cookie) } == [0])
	}

	@Test func everyEntryIsPackedOnceOverAllCalls() throws {
		let requests = OSAllocatedUnfairLock(initialState: [ReaddirRequest]())
		let server = try Self.server(count: 12, pageSize: 8, requests: requests)
		let client = try BridgeClient(manifest: server.manifest)
		let lister = DirectoryLister(client: client)

		let listing = try lister.listAll(directory: Self.directory, capacity: 3, counting: client)

		#expect(listing.names == Self.names(0 ..< 12))
	}

	@Test func aCallContinuesWithTheNextPageAfterTheKeptEntries() throws {
		let requests = OSAllocatedUnfairLock(initialState: [ReaddirRequest]())
		let server = try Self.server(count: 12, pageSize: 8, requests: requests)
		let client = try BridgeClient(manifest: server.manifest)
		let lister = DirectoryLister(client: client)

		let first = try lister.list(directory: Self.directory, cookie: 0, verifier: 0, capacity: 3)
		let second = try lister.list(directory: Self.directory, cookie: 3, verifier: first.verifier, capacity: 100)

		#expect(first.verifier == Self.verifier)
		#expect(second.verifier == Self.verifier)
		#expect(second.names == Self.names(3 ..< 12))
	}

	@Test(arguments: ["another request", "another verifier", "attributes", "another directory"])
	func aCallThatContinuesSomethingElseAsksTheServer(difference: String) throws {
		let requests = OSAllocatedUnfairLock(initialState: [ReaddirRequest]())
		let server = try Self.server(count: 12, pageSize: 8, requests: requests)
		let client = try BridgeClient(manifest: server.manifest)
		let lister = DirectoryLister(client: client)
		let first = try lister.list(directory: Self.directory, cookie: 0, verifier: 0, capacity: 3)
		if difference == "another request" {
			_ = try client.request(GetattrRequest(nodeId: Self.directory))
		}

		let second = try lister.list(
			directory: difference == "another directory" ? Self.directory + 1 : Self.directory,
			cookie: 3,
			verifier: difference == "another verifier" ? first.verifier + 1 : first.verifier,
			wantAttributes: difference == "attributes",
			capacity: 3
		)

		#expect(second.names == Self.names(3 ..< 6))
		#expect(requests.withLock { $0.map(\.cookie) } == [0, 3])
	}

	@Test func aCallAtCookieZeroAsksTheServerAlthoughTheEntryAtCookieZeroWasRefused() throws {
		let requests = OSAllocatedUnfairLock(initialState: [ReaddirRequest]())
		let server = try Self.server(count: 12, pageSize: 8, requests: requests)
		let client = try BridgeClient(manifest: server.manifest)
		let lister = DirectoryLister(client: client)

		let first = try lister.list(directory: Self.directory, cookie: 0, verifier: 0, capacity: 0)
		let second = try lister.list(directory: Self.directory, cookie: 0, verifier: first.verifier, capacity: 3)

		#expect(second.names == Self.names(0 ..< 3))
		#expect(requests.withLock { $0.map(\.cookie) } == [0, 0])
	}
}
