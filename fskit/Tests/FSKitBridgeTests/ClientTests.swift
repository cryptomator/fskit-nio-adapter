import Foundation
import Testing
import os

@testable import FSKitBridge

/// Plays the server's part of the bridge protocol on a loopback port: accepts one connection, answers its handshake and hands every other request to a closure.
final class ScriptedServer: Sendable {

	/// After this many requests the server closes the connection, so that a client that never stops asking fails its test instead of hanging it.
	private static let maxRequests = 16

	let manifest: Manifest
	private let listener: Int32

	/// - Parameter respond: Returns the response to a request, or `nil` to close the connection instead of answering.
	init(respond: @escaping @Sendable (Frame) throws -> Frame?) throws {
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
		manifest = Manifest(protocolVersion: Messages.protocolVersion, port: UInt16(bigEndian: address.sin_port), token: Data(count: Messages.tokenLength), volumeName: "Scripted")
		Thread.detachNewThread {
			let connection = accept(listener, nil, nil)
			guard connection >= 0 else {
				return
			}
			defer { close(connection) }
			do {
				for _ in 0...Self.maxRequests {
					let request = try FrameCodec.decode { try Self.receive(connection, $0) }
					let response = request.opcode == .hello ? Messages.frame(for: HelloResponse(), opcode: .hello, requestId: request.requestId) : try respond(request)
					guard let response else {
						return
					}
					try Self.send(connection, FrameCodec.encode(response))
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
				if result < 0 && errno == EINTR {
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
				if result < 0 && errno == EINTR {
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

@Suite struct BridgeClientTests {

	private static let file: UInt64 = 64
	private static let time = Timestamp(seconds: 1_700_000_000, nanos: 0)

	private static func attributes(size: Int) -> Attributes {
		Attributes(type: .file, mode: 0o644, size: UInt64(size), nodeId: file, parentId: Messages.rootNodeId, modified: time, accessed: time, created: time)
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
				stored.replaceSubrange(offset..<(offset + accepted.count), with: accepted)
				return stored.count
			}
			return Messages.frame(for: WriteResponse(written: UInt32(accepted.count), attributes: attributes(size: size), usableBytes: 1), opcode: .write, requestId: frame.requestId)
		}
	}

	/// A server that serves `content` and returns at most `limit` bytes per request.
	private static func readableServer(content: Data, requests: OSAllocatedUnfairLock<[ReadRequest]>, limit: Int) throws -> ScriptedServer {
		try ScriptedServer { frame in
			let request = try Messages.decodeRequest(ReadRequest.self, from: frame)
			requests.withLock { $0.append(request) }
			let start = min(Int(request.offset), content.count)
			let end = min(start + min(Int(request.length), limit), content.count)
			return Messages.frame(for: ReadResponse(attributes: attributes(size: content.count), data: content.subdata(in: start..<end)), opcode: .read, requestId: frame.requestId)
		}
	}

	@Test func splitsAWriteByThePayloadLimit() throws {
		let content = Data(testContentOfLength: 2 * FrameCodec.maxPayloadLength + 5)
		let stored = OSAllocatedUnfairLock(initialState: Data())
		let requests = OSAllocatedUnfairLock(initialState: [Int]())
		let server = try Self.writableServer(stored: stored, requests: requests, limit: .max)
		let client = try BridgeClient(manifest: server.manifest)

		let response = try client.write(nodeId: Self.file, offset: 100, data: content)

		#expect(Int(response.written) == content.count)
		#expect(Int(response.attributes.size) == 100 + content.count)
		#expect(requests.withLock { $0 } == [FrameCodec.maxPayloadLength, FrameCodec.maxPayloadLength, 5])
		#expect(stored.withLock { $0 } == Data(count: 100) + content)
	}

	@Test func continuesAWriteTheServerAcceptedInPart() throws {
		let content = Data(testContentOfLength: 2500)
		let stored = OSAllocatedUnfairLock(initialState: Data())
		let requests = OSAllocatedUnfairLock(initialState: [Int]())
		let server = try Self.writableServer(stored: stored, requests: requests, limit: 1000)
		let client = try BridgeClient(manifest: server.manifest)

		let response = try client.write(nodeId: Self.file, offset: 0, data: content)

		#expect(response.written == 2500)
		#expect(requests.withLock { $0 } == [2500, 1500, 500])
		#expect(stored.withLock { $0 } == content)
	}

	@Test func endsAWriteTheServerAcceptsNothingOf() throws {
		let stored = OSAllocatedUnfairLock(initialState: Data())
		let requests = OSAllocatedUnfairLock(initialState: [Int]())
		let server = try Self.writableServer(stored: stored, requests: requests, limit: 0)
		let client = try BridgeClient(manifest: server.manifest)

		let response = try client.write(nodeId: Self.file, offset: 0, data: Data(testContentOfLength: 10))

		#expect(response.written == 0)
		#expect(requests.withLock { $0 } == [10])
	}

	@Test func splitsAReadByThePayloadLimit() throws {
		let content = Data(testContentOfLength: 2 * FrameCodec.maxPayloadLength + 5)
		let requests = OSAllocatedUnfairLock(initialState: [ReadRequest]())
		let server = try Self.readableServer(content: content, requests: requests, limit: .max)
		let client = try BridgeClient(manifest: server.manifest)

		let response = try client.read(nodeId: Self.file, offset: 3, length: content.count + 100)

		#expect(response.data == content.dropFirst(3))
		#expect(Int(response.attributes.size) == content.count)
		let max = UInt64(FrameCodec.maxPayloadLength)
		#expect(requests.withLock { $0 } == [
			ReadRequest(nodeId: Self.file, offset: 3, length: UInt32(max)),
			ReadRequest(nodeId: Self.file, offset: 3 + max, length: UInt32(max)),
			ReadRequest(nodeId: Self.file, offset: 3 + 2 * max, length: 105),
		])
	}

	@Test func readsNoMoreThanRequested() throws {
		let content = Data(testContentOfLength: FrameCodec.maxPayloadLength + 500)
		let requests = OSAllocatedUnfairLock(initialState: [ReadRequest]())
		let server = try Self.readableServer(content: content, requests: requests, limit: .max)
		let client = try BridgeClient(manifest: server.manifest)

		let response = try client.read(nodeId: Self.file, offset: 0, length: FrameCodec.maxPayloadLength + 10)

		#expect(response.data == content.prefix(FrameCodec.maxPayloadLength + 10))
		#expect(requests.withLock { $0.map(\.length) } == [UInt32(FrameCodec.maxPayloadLength), 10])
	}

	@Test func endsAReadAtAShortResponse() throws {
		let content = Data(testContentOfLength: 5000)
		let requests = OSAllocatedUnfairLock(initialState: [ReadRequest]())
		let server = try Self.readableServer(content: content, requests: requests, limit: 1000)
		let client = try BridgeClient(manifest: server.manifest)

		let response = try client.read(nodeId: Self.file, offset: 0, length: 4000)

		#expect(response.data == content.prefix(1000))
		#expect(requests.withLock { $0.count } == 1)
	}

	@Test func reportsAStatusAndKeepsTheConnection() throws {
		let server = try ScriptedServer { frame in
			frame.opcode == .lookup ? Messages.frame(forFailure: ENOENT, opcode: .lookup, requestId: frame.requestId) : Messages.frame(for: SyncResponse(usableBytes: 7), opcode: frame.opcode, requestId: frame.requestId)
		}
		let client = try BridgeClient(manifest: server.manifest)

		#expect(throws: StatusError(status: ENOENT)) {
			try client.request(LookupRequest(parentId: Messages.rootNodeId, name: "missing"))
		}
		#expect(try client.request(SyncRequest()).usableBytes == 7)
	}

	@Test(arguments: ["requestId", "opcode", "kind", "control"])
	func dropsTheConnectionOnAResponseThatDoesNotMatch(mismatch: String) throws {
		let answered = OSAllocatedUnfairLock(initialState: 0)
		let server = try ScriptedServer { frame in
			answered.withLock { $0 += 1 }
			var response = Messages.frame(for: SyncResponse(usableBytes: 7), opcode: .sync, requestId: frame.requestId)
			switch mismatch {
			case "requestId": response.requestId += 1
			case "opcode": response.opcode = .forget
			case "kind": response.kind = .request
			default: response.control.append(0)
			}
			return response
		}
		let client = try BridgeClient(manifest: server.manifest)

		#expect(throws: StatusError(status: EIO)) {
			try client.request(SyncRequest())
		}
		#expect(throws: StatusError(status: EIO)) {
			try client.request(SyncRequest())
		}
		#expect(answered.withLock { $0 } == 1)
	}

	@Test func failsWithEIOOnceTheServerClosedTheConnection() throws {
		let server = try ScriptedServer { _ in nil }
		let client = try BridgeClient(manifest: server.manifest)

		#expect(throws: StatusError(status: EIO)) {
			try client.request(SyncRequest())
		}
		#expect(throws: StatusError(status: EIO)) {
			try client.request(SyncRequest())
		}
	}
}
