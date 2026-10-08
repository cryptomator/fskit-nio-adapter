import Foundation
import os
import Testing
@testable import FSKitBridge

/// Plays the server's part of the bridge protocol on a loopback port: accepts one connection, answers its handshake and hands every other request to a closure.
final class ScriptedServer: Sendable {
	/// After this many requests the server closes the connection, so that a client that never stops asking fails its test instead of hanging it.
	private static let maxRequests = 16

	let manifest: Manifest
	private let listener: Int32

	/// - Parameters:
	///   - batches: One number per batch: how many requests the server reads before it answers them, which it does in reverse order. Requests after the last batch are answered one at a time.
	///   - respond: Returns the response to a request, or `nil` to close the connection instead of answering.
	init(batches: [Int] = [], respond: @escaping @Sendable (Frame) throws -> Frame?) throws {
		let listener = socket(AF_INET, SOCK_STREAM, 0)
		var address = sockaddr_in()
		address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
		address.sin_family = sa_family_t(AF_INET)
		address.sin_addr = in_addr(s_addr: INADDR_LOOPBACK.bigEndian)
		var length = socklen_t(MemoryLayout<sockaddr_in>.size)
		let bound = withUnsafeMutablePointer(to: &address) {
			$0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
				bind(listener, $0, length) == 0 && listen(listener, 1) == 0 && getsockname(listener, $0, &length) == 0
			}
		}
		try #require(bound)
		self.listener = listener
		self.manifest = Manifest(protocolVersion: Messages.protocolVersion, port: UInt16(bigEndian: address.sin_port), token: Data(count: Messages.tokenLength), volumeName: "Scripted")
		Thread.detachNewThread {
			let connection = accept(listener, nil, nil)
			guard connection >= 0 else {
				return
			}
			defer { close(connection) }
			do {
				let hello = try FrameCodec.decode { try Self.receive(connection, $0) }
				try Self.send(connection, FrameCodec.encode(Messages.frame(for: HelloResponse(), opcode: .hello, requestId: hello.requestId)))
				var batches = batches[...]
				var answered = 0
				while answered < Self.maxRequests {
					let size = batches.popFirst() ?? 1
					let requests = try (0 ..< size).map { _ in try FrameCodec.decode { try Self.receive(connection, $0) } }
					for request in requests.reversed() {
						guard let response = try respond(request) else {
							return
						}
						try Self.send(connection, FrameCodec.encode(response))
					}
					answered += size
				}
			} catch {
				// the client is gone
			}
		}
	}

	deinit {
		close(listener)
	}

	private static func receive(_ connection: Int32, _ count: Int) throws -> Data {
		var data = Data(count: count)
		try data.withUnsafeMutableBytes { buffer in
			var received = 0
			while received < count {
				let result = recv(connection, buffer.baseAddress! + received, count - received, 0)
				if result < 0, errno == EINTR {
					continue
				}
				guard result > 0 else {
					throw POSIXError(.EIO)
				}
				received += result
			}
		}
		return data
	}

	private static func send(_ connection: Int32, _ data: Data) throws {
		try data.withUnsafeBytes { buffer in
			var sent = 0
			while sent < buffer.count {
				let result = Darwin.send(connection, buffer.baseAddress! + sent, buffer.count - sent, 0)
				if result < 0, errno == EINTR {
					continue
				}
				guard result > 0 else {
					throw POSIXError(.EIO)
				}
				sent += result
			}
		}
	}
}

/// Waits until a client has sent the given number of requests.
func waitUntil(_ client: BridgeClient, hasSent count: UInt64) async throws {
	while client.requestCounts.sent < count {
		try await Task.sleep(for: .milliseconds(1))
	}
}

struct BridgeClientTests {
	private static let file: UInt64 = 64
	private static let time = Timestamp(seconds: 1_700_000_000, nanos: 0)
	private static let freeSpace = FreeSpace(usableBytes: 1, generation: 1)

	private static func attributes(size: Int, nodeId: UInt64 = file) -> Attributes {
		Attributes(type: .file, mode: 0o644, size: UInt64(size), nodeId: nodeId, parentId: Messages.rootNodeId, modified: time, accessed: time, created: time, generation: 1)
	}

	/// A server that stores what it is sent at the given offsets and accepts at most `limit` bytes per request.
	private static func writableServer(stored: OSAllocatedUnfairLock<Data>, requests: OSAllocatedUnfairLock<[Int]>, limit: Int) throws -> ScriptedServer {
		try ScriptedServer { frame in
			let request = try Messages.decodeRequest(WriteRequest.self, from: frame)
			let accepted = request.data.prefix(limit)
			requests.withLock { $0.append(request.data.count) }
			let size = stored.withLock { stored in
				let offset = Int(request.offset)
				if stored.count < offset + accepted.count {
					stored.append(Data(count: offset + accepted.count - stored.count))
				}
				stored.replaceSubrange(offset ..< (offset + accepted.count), with: accepted)
				return stored.count
			}
			return Messages.frame(for: WriteResponse(written: UInt32(accepted.count), attributes: attributes(size: size), freeSpace: freeSpace), opcode: .write, requestId: frame.requestId)
		}
	}

	/// A server that serves `content` and returns at most `limit` bytes per request.
	private static func readableServer(content: Data, requests: OSAllocatedUnfairLock<[ReadRequest]>, limit: Int) throws -> ScriptedServer {
		try ScriptedServer { frame in
			let request = try Messages.decodeRequest(ReadRequest.self, from: frame)
			requests.withLock { $0.append(request) }
			let start = min(Int(request.offset), content.count)
			let end = min(start + min(Int(request.length), limit), content.count)
			return Messages.frame(for: ReadResponse(attributes: attributes(size: content.count), data: content.subdata(in: start ..< end)), opcode: .read, requestId: frame.requestId)
		}
	}

	/// A successful response to each request `send(_:through:)` makes.
	private static func success(for frame: Frame) -> Frame {
		let attributes = attributes(size: 0)
		return switch frame.opcode {
		case .write: Messages.frame(for: WriteResponse(written: 1, attributes: attributes, freeSpace: freeSpace), opcode: .write, requestId: frame.requestId)
		case .setattr: Messages.frame(for: SetattrResponse(applied: Messages.attributeSize, attributes: attributes, freeSpace: freeSpace), opcode: .setattr, requestId: frame.requestId)
		case .create: Messages.frame(for: CreateResponse(attributes: attributes, name: "new", directoryAttributes: attributes, freeSpace: freeSpace), opcode: .create, requestId: frame.requestId)
		case .getattr: Messages.frame(for: GetattrResponse(attributes: attributes), opcode: .getattr, requestId: frame.requestId)
		case .remove: Messages.frame(for: RemoveResponse(attributes: attributes, directoryAttributes: attributes, freeSpace: freeSpace), opcode: .remove, requestId: frame.requestId)
		default: Messages.frame(for: SyncResponse(freeSpace: freeSpace), opcode: frame.opcode, requestId: frame.requestId)
		}
	}

	private static func send(_ opcode: Opcode, through client: BridgeClient) async throws {
		switch opcode {
		case .write: _ = try await client.request(WriteRequest(nodeId: file, offset: 0, data: Data([1])))
		case .setattr: _ = try await client.request(SetattrRequest(nodeId: file, valid: Messages.attributeSize, size: 0, mode: 0, accessed: time, modified: time))
		case .create: _ = try await client.request(CreateRequest(parentId: Messages.rootNodeId, name: "new", type: .file, mode: 0o644))
		case .getattr: _ = try await client.request(GetattrRequest(nodeId: file))
		case .remove: _ = try await client.request(RemoveRequest(nodeId: file, parentId: Messages.rootNodeId, name: "file"))
		default: _ = try await client.request(SyncRequest())
		}
	}

	@Test func aNewClientCanAnswerASyncItself() throws {
		let server = try ScriptedServer { Self.success(for: $0) }
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		#expect(client.canAnswerSyncItself)
	}

	@Test(arguments: [Opcode.write, .setattr, .create], [true, false])
	func aRequestThatMayLeaveDataInAChannelKeepsTheClientFromAnsweringASyncItselfWhateverItsOutcome(opcode: Opcode, succeeds: Bool) async throws {
		let server = try ScriptedServer { frame in
			succeeds ? Self.success(for: frame) : Messages.frame(forFailure: ENOSPC, opcode: frame.opcode, requestId: frame.requestId)
		}
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		_ = try? await Self.send(opcode, through: client)

		#expect(!client.canAnswerSyncItself)
	}

	@Test(arguments: [Opcode.getattr, .remove])
	func anotherRequestLeavesItAbleTo(opcode: Opcode) async throws {
		let server = try ScriptedServer { Self.success(for: $0) }
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		try await Self.send(opcode, through: client)

		#expect(client.canAnswerSyncItself)
	}

	@Test func aSuccessfulSyncLetsTheClientAnswerSyncsItselfAndAFailedOneDoesNot() async throws {
		let syncs = OSAllocatedUnfairLock(initialState: 0)
		let server = try ScriptedServer { frame in
			guard frame.opcode == .sync, syncs.withLock({ $0 += 1; return $0 }) == 1 else {
				return Self.success(for: frame)
			}
			return Messages.frame(forFailure: EIO, opcode: .sync, requestId: frame.requestId)
		}
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }
		try await Self.send(.write, through: client)

		await #expect(throws: StatusError(status: EIO)) {
			try await client.request(SyncRequest())
		}
		#expect(!client.canAnswerSyncItself)
		_ = try await client.request(SyncRequest())
		#expect(client.canAnswerSyncItself)
	}

	@Test func aSyncSentWhileAWriteIsInFlightDoesNotCoverIt() async throws {
		// the write and the sync are read together and answered in reverse order, so the sync succeeds while the write is in flight
		let server = try ScriptedServer(batches: [2]) { Self.success(for: $0) }
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		async let write: Void = Self.send(.write, through: client)
		try await waitUntil(client, hasSent: 1)
		_ = try await client.request(SyncRequest())
		try await write

		#expect(!client.canAnswerSyncItself)
		_ = try await client.request(SyncRequest())
		#expect(client.canAnswerSyncItself)
	}

	@Test func concurrentRequestsEachGetTheirOwnResponse() async throws {
		let server = try ScriptedServer(batches: [3]) { frame in
			let request = try Messages.decodeRequest(GetattrRequest.self, from: frame)
			return Messages.frame(for: GetattrResponse(attributes: Self.attributes(size: 0, nodeId: request.nodeId)), opcode: .getattr, requestId: frame.requestId)
		}
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		let answered = try await withThrowingTaskGroup(of: (UInt64, UInt64).self) { group in
			for nodeId: UInt64 in [64, 65, 66] {
				group.addTask {
					try await (nodeId, client.request(GetattrRequest(nodeId: nodeId)).attributes.nodeId)
				}
			}
			return try await group.reduce(into: [(UInt64, UInt64)]()) { $0.append($1) }
		}

		#expect(answered.count == 3)
		#expect(answered.allSatisfy { $0.0 == $0.1 })
	}

	@Test(arguments: ["unknown requestId", "closed connection"])
	func aLostOrMismatchedConnectionFailsEveryWaitingCall(failure: String) async throws {
		let server = try ScriptedServer(batches: [2]) { frame in
			guard failure == "unknown requestId" else {
				return nil
			}
			return Messages.frame(for: SyncResponse(freeSpace: Self.freeSpace), opcode: .sync, requestId: frame.requestId + 100)
		}
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		async let waiting = Result { try await client.request(SyncRequest()) }
		try await waitUntil(client, hasSent: 1)
		let second = await Result { try await client.request(SyncRequest()) }
		let first = await waiting

		#expect(throws: StatusError(status: EIO)) {
			try first.get()
		}
		#expect(throws: StatusError(status: EIO)) {
			try second.get()
		}
		await #expect(throws: StatusError(status: EIO)) {
			try await client.request(SyncRequest())
		}
	}

	@Test func splitsAWriteByThePayloadLimit() async throws {
		let content = Data(testContentOfLength: 2 * FrameCodec.maxPayloadLength + 5)
		let stored = OSAllocatedUnfairLock(initialState: Data())
		let requests = OSAllocatedUnfairLock(initialState: [Int]())
		let server = try Self.writableServer(stored: stored, requests: requests, limit: .max)
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		let response = try await client.write(nodeId: Self.file, offset: 100, data: content)

		#expect(Int(response.written) == content.count)
		#expect(Int(response.attributes.size) == 100 + content.count)
		#expect(requests.withLock { $0 } == [FrameCodec.maxPayloadLength, FrameCodec.maxPayloadLength, 5])
		#expect(stored.withLock { $0 } == Data(count: 100) + content)
	}

	@Test func continuesAWriteTheServerAcceptedInPart() async throws {
		let content = Data(testContentOfLength: 2500)
		let stored = OSAllocatedUnfairLock(initialState: Data())
		let requests = OSAllocatedUnfairLock(initialState: [Int]())
		let server = try Self.writableServer(stored: stored, requests: requests, limit: 1000)
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		let response = try await client.write(nodeId: Self.file, offset: 0, data: content)

		#expect(response.written == 2500)
		#expect(requests.withLock { $0 } == [2500, 1500, 500])
		#expect(stored.withLock { $0 } == content)
	}

	@Test func endsAWriteTheServerAcceptsNothingOf() async throws {
		let stored = OSAllocatedUnfairLock(initialState: Data())
		let requests = OSAllocatedUnfairLock(initialState: [Int]())
		let server = try Self.writableServer(stored: stored, requests: requests, limit: 0)
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		let response = try await client.write(nodeId: Self.file, offset: 0, data: Data(testContentOfLength: 10))

		#expect(response.written == 0)
		#expect(requests.withLock { $0 } == [10])
	}

	@Test func splitsAReadByThePayloadLimit() async throws {
		let content = Data(testContentOfLength: 2 * FrameCodec.maxPayloadLength + 5)
		let requests = OSAllocatedUnfairLock(initialState: [ReadRequest]())
		let server = try Self.readableServer(content: content, requests: requests, limit: .max)
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		let response = try await client.read(nodeId: Self.file, offset: 3, length: content.count + 100)

		#expect(response.data == content.dropFirst(3))
		#expect(Int(response.attributes.size) == content.count)
		let max = UInt64(FrameCodec.maxPayloadLength)
		#expect(requests.withLock { $0 } == [
			ReadRequest(nodeId: Self.file, offset: 3, length: UInt32(max)),
			ReadRequest(nodeId: Self.file, offset: 3 + max, length: UInt32(max)),
			ReadRequest(nodeId: Self.file, offset: 3 + 2 * max, length: 105)
		])
	}

	@Test func readsNoMoreThanRequested() async throws {
		let content = Data(testContentOfLength: FrameCodec.maxPayloadLength + 500)
		let requests = OSAllocatedUnfairLock(initialState: [ReadRequest]())
		let server = try Self.readableServer(content: content, requests: requests, limit: .max)
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		let response = try await client.read(nodeId: Self.file, offset: 0, length: FrameCodec.maxPayloadLength + 10)

		#expect(response.data == content.prefix(FrameCodec.maxPayloadLength + 10))
		#expect(requests.withLock { $0.map(\.length) } == [UInt32(FrameCodec.maxPayloadLength), 10])
	}

	@Test func endsAReadAtAShortResponse() async throws {
		let content = Data(testContentOfLength: 5000)
		let requests = OSAllocatedUnfairLock(initialState: [ReadRequest]())
		let server = try Self.readableServer(content: content, requests: requests, limit: 1000)
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		let response = try await client.read(nodeId: Self.file, offset: 0, length: 4000)

		#expect(response.data == content.prefix(1000))
		#expect(requests.withLock { $0.count } == 1)
	}

	@Test func reportsAStatusAndKeepsTheConnection() async throws {
		let server = try ScriptedServer { frame in
			frame.opcode == .lookup ? Messages.frame(forFailure: ENOENT, opcode: .lookup, requestId: frame.requestId) : Messages.frame(for: SyncResponse(freeSpace: FreeSpace(usableBytes: 7, generation: 1)), opcode: frame.opcode, requestId: frame.requestId)
		}
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		await #expect(throws: StatusError(status: ENOENT)) {
			try await client.request(LookupRequest(parentId: Messages.rootNodeId, name: "missing"))
		}
		#expect(try await client.request(SyncRequest()).freeSpace.usableBytes == 7)
	}

	@Test(arguments: ["requestId", "opcode", "kind", "control"])
	func dropsTheConnectionOnAResponseThatDoesNotMatch(mismatch: String) async throws {
		let answered = OSAllocatedUnfairLock(initialState: 0)
		let server = try ScriptedServer { frame in
			answered.withLock { $0 += 1 }
			var response = Messages.frame(for: SyncResponse(freeSpace: Self.freeSpace), opcode: .sync, requestId: frame.requestId)
			switch mismatch {
			case "requestId": response.requestId += 1
			case "opcode": response.opcode = .forget
			case "kind": response.kind = .request
			default: response.control.append(0)
			}
			return response
		}
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		await #expect(throws: StatusError(status: EIO)) {
			try await client.request(SyncRequest())
		}
		await #expect(throws: StatusError(status: EIO)) {
			try await client.request(SyncRequest())
		}
		#expect(answered.withLock { $0 } == 1)
	}

	@Test func disconnectingFailsACallWhoseRequestWaitsForTheServerToRead() async throws {
		let reading = DispatchSemaphore(value: 0)
		let server = try ScriptedServer { _ in
			reading.wait()
			return nil
		}
		defer { reading.signal() }
		let client = try BridgeClient(manifest: server.manifest)
		// more than the connection's buffers hold, which grow while the server reads the first at full speed
		let count = 16
		let data = Data(count: FrameCodec.maxPayloadLength)
		let armed = OSAllocatedUnfairLock(initialState: false)
		// a thread of its own, since the calls that wait to send hold the threads tasks run on
		Thread.detachNewThread {
			// once a call waits in its send, it keeps the others from sending, and the number of sent requests stops growing
			var sent = client.requestCounts.sent
			let deadline = Date.now.addingTimeInterval(5)
			while Date.now < deadline {
				Thread.sleep(forTimeInterval: 0.2)
				let now = client.requestCounts.sent
				if now == sent, now >= 2 {
					break
				}
				sent = now
			}
			let stalled = sent
			armed.withLock { $0 = stalled >= 2 && stalled < UInt64(count) }
			client.disconnect()
		}

		await withTaskGroup(of: Void.self) { group in
			for offset in 0 ..< count {
				group.addTask {
					await #expect(throws: StatusError(status: EIO)) {
						try await client.request(WriteRequest(nodeId: Self.file, offset: UInt64(offset), data: data))
					}
				}
			}
		}
		#expect(armed.withLock { $0 })
	}

	@Test func failsWithEIOOnceTheServerClosedTheConnection() async throws {
		let server = try ScriptedServer { _ in nil }
		let client = try BridgeClient(manifest: server.manifest)
		defer { client.disconnect() }

		await #expect(throws: StatusError(status: EIO)) {
			try await client.request(SyncRequest())
		}
		await #expect(throws: StatusError(status: EIO)) {
			try await client.request(SyncRequest())
		}
	}
}

extension Result where Failure == any Error {
	init(catching body: () async throws -> Success) async {
		do {
			self = try await .success(body())
		} catch {
			self = .failure(error)
		}
	}
}
