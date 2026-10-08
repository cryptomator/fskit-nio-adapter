import Foundation
import os

/// Connects to the server of one mount and exchanges requests and responses with it.
///
/// Calls may overlap: each sends its request at once, and a thread of the client's own reads the responses and hands each to the call whose request id it carries. The server answers in any order.
public final class BridgeClient: Sendable {
	/// How many requests were sent and how many of them have completed, counted when a call sends its request and when it returns.
	public struct RequestCounts: Equatable, Sendable {
		public var sent: UInt64 = 0
		public var completed: UInt64 = 0
		/// The `WRITE`, `SETATTR` and `CREATE` requests among them. Only these leave data in a channel of the server that a `SYNC` forces, see `protocol/PROTOCOL.md`.
		var dirtying: UInt64 = 0
		var dirtyingCompleted: UInt64 = 0

		/// Whether no request is in flight.
		public var isQuiet: Bool {
			sent == completed
		}
	}

	private struct Waiter: Sendable {
		let opcode: Opcode
		let continuation: CheckedContinuation<Frame, any Error>
	}

	private struct State: Sendable {
		var connected = true
		/// Whether the descriptor is closed, which only the reader thread does, holding the send lock and this one. A shutdown therefore never reaches a reused descriptor: `disconnect` checks this under this lock, and a failed send holds the send lock.
		var closed = false
		var nextRequestId: UInt64 = 1
		var waiting: [UInt64: Waiter] = [:]
		var counts = RequestCounts()
		/// The number of dirtying requests that a successful `SYNC` covers.
		var synced: UInt64 = 0
	}

	/// Why the connection was lost, for the log.
	private enum ConnectionError: Error {
		case sending(Int32)
		case receiving(Int32)
		case closedByServer
	}

	private let logger = Logger(subsystem: "org.cryptomator.fskit", category: "BridgeClient")
	private let descriptor: Int32
	/// Held while a request is registered and sent, so that frames reach the connection whole and in the order of their ids.
	private let sending = NSLock()
	private let state = OSAllocatedUnfairLock(initialState: State())

	/// Connects to the loopback port named in the manifest, performs the handshake and starts reading responses.
	public init(manifest: Manifest) throws {
		let descriptor = socket(AF_INET, SOCK_STREAM, 0)
		guard descriptor >= 0 else {
			throw StatusError(status: errno)
		}
		self.descriptor = descriptor
		do {
			// writing to a connection the server has closed must fail the call, not kill the process
			var enabled: Int32 = 1
			setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &enabled, socklen_t(MemoryLayout<Int32>.size))
			var address = sockaddr_in()
			address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
			address.sin_family = sa_family_t(AF_INET)
			address.sin_port = manifest.port.bigEndian
			address.sin_addr = in_addr(s_addr: INADDR_LOOPBACK.bigEndian)
			let connected = withUnsafePointer(to: &address) {
				$0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
					connect(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
				}
			}
			guard connected == 0 else {
				throw StatusError(status: errno)
			}
			try handshake(manifest)
		} catch {
			close(descriptor)
			throw error
		}
		Thread.detachNewThread {
			self.readResponses()
		}
	}

	public var requestCounts: RequestCounts {
		state.withLock { $0.counts }
	}

	/// Whether a sync can be answered without a request: every `WRITE`, `SETATTR` and `CREATE` sent was followed by a successful `SYNC` sent after it completed, and none is in flight.
	public var canAnswerSyncItself: Bool {
		state.withLock { $0.synced == $0.counts.dirtying && $0.counts.dirtyingCompleted == $0.counts.dirtying }
	}

	/// Ends the connection. Every call in flight and every later one fails with `EIO`.
	public func disconnect() {
		failEveryCall()
		state.withLock { state in
			if !state.closed {
				// wakes the reader thread, which closes the descriptor, and fails a send that waits for the server to read
				shutdown(descriptor, SHUT_RDWR)
			}
		}
	}

	/// Sends a request and waits for its response.
	///
	/// - Throws: `StatusError` with the status the server reported, which leaves the connection usable. `StatusError` with `EIO` if the connection is or gets lost or a response does not match a request in flight. After such a loss or mismatch, every call fails with `EIO`.
	public func request<R: Request>(_ request: R) async throws -> R.Response {
		let dirtying = R.opcode == .write || R.opcode == .setattr || R.opcode == .create
		var sent: Sent?
		defer {
			if sent != nil {
				state.withLock { state in
					state.counts.completed += 1
					if dirtying {
						state.counts.dirtyingCompleted += 1
					}
				}
			}
		}
		let frame: Frame = try await withCheckedThrowingContinuation { continuation in
			sent = send(request, dirtying: dirtying, resuming: continuation)
		}
		do {
			let response: R.Response = try Messages.decodeResponse(from: frame)
			if let syncMark = sent?.syncMark {
				state.withLock { $0.synced = max($0.synced, syncMark) }
			}
			return response
		} catch let status as StatusError {
			throw status
		} catch {
			logger.error("Dropping the connection after request \(frame.requestId) (\(String(describing: R.opcode), privacy: .public)): \(String(describing: error), privacy: .public)")
			disconnect()
			throw StatusError(status: EIO)
		}
	}

	private struct Sent {
		/// For a `SYNC`, the number of dirtying requests it covers if it succeeds.
		let syncMark: UInt64?
	}

	/// Registers a request, so that its response resumes the continuation, and sends it.
	///
	/// - Returns: `nil` if the connection was already lost, so that nothing was registered. The continuation is resumed with `EIO` then.
	private func send<R: Request>(_ request: R, dirtying: Bool, resuming continuation: CheckedContinuation<Frame, any Error>) -> Sent? {
		sending.withLock {
			let registered: (requestId: UInt64, sent: Sent)? = state.withLock { state in
				guard state.connected else {
					return nil
				}
				let requestId = state.nextRequestId
				state.nextRequestId += 1
				state.waiting[requestId] = Waiter(opcode: R.opcode, continuation: continuation)
				state.counts.sent += 1
				if dirtying {
					state.counts.dirtying += 1
				}
				// a SYNC covers the dirtying requests that completed before it was sent, and only if none is still in flight
				let covered = R.opcode == .sync && state.counts.dirtyingCompleted == state.counts.dirtying
				return (requestId, Sent(syncMark: covered ? state.counts.dirtying : nil))
			}
			guard let registered else {
				continuation.resume(throwing: StatusError(status: EIO))
				return nil
			}
			do {
				try send(FrameCodec.encode(Messages.frame(for: request, requestId: registered.requestId)))
			} catch {
				logger.error("Dropping the connection after failing to send request \(registered.requestId) (\(String(describing: R.opcode), privacy: .public)): \(String(describing: error), privacy: .public)")
				failEveryCall()
				shutdown(descriptor, SHUT_RDWR)
			}
			return registered.sent
		}
	}

	/// Reads up to `length` bytes, split into as many requests as the payload limit requires.
	public func read(nodeId: UInt64, offset: UInt64, length: Int) async throws -> ReadResponse {
		var data = Data()
		var attributes: Attributes
		repeat {
			let requested = min(length - data.count, FrameCodec.maxPayloadLength)
			let response = try await request(ReadRequest(nodeId: nodeId, offset: offset + UInt64(data.count), length: UInt32(requested)))
			attributes = response.attributes
			data.append(response.data)
			if response.data.count < requested {
				break // end of file
			}
		} while data.count < length
		return ReadResponse(attributes: attributes, data: data)
	}

	/// Writes `data`, split into as many requests as the payload limit requires, until all of it is written or the server accepts nothing more. The response's `written` is the total.
	public func write(nodeId: UInt64, offset: UInt64, data: Data) async throws -> WriteResponse {
		var written = 0
		var response: WriteResponse
		repeat {
			let chunk = data.dropFirst(written).prefix(FrameCodec.maxPayloadLength)
			response = try await request(WriteRequest(nodeId: nodeId, offset: offset + UInt64(written), data: Data(chunk)))
			written += Int(response.written)
		} while written < data.count && response.written > 0
		response.written = UInt32(written)
		return response
	}

	private func handshake(_ manifest: Manifest) throws {
		let requestId = state.withLock { state in
			defer { state.nextRequestId += 1 }
			return state.nextRequestId
		}
		try send(FrameCodec.encode(Messages.frame(for: HelloRequest(magic: Messages.magic, protocolVersion: Messages.protocolVersion, token: manifest.token), requestId: requestId)))
		let frame = try FrameCodec.decode(readingFrom: receive)
		guard frame.kind == .response, frame.opcode == .hello, frame.requestId == requestId else {
			throw ProtocolError("Response does not match the handshake")
		}
		_ = try Messages.decodeResponse(HelloResponse.self, from: frame)
	}

	/// Hands every response to the call that waits for it, until the connection ends or a response matches no call.
	private func readResponses() {
		do {
			while true {
				let frame = try FrameCodec.decode(readingFrom: receive)
				let waiter = state.withLock { $0.waiting.removeValue(forKey: frame.requestId) }
				guard let waiter, frame.kind == .response, frame.opcode == waiter.opcode else {
					waiter?.continuation.resume(throwing: StatusError(status: EIO))
					throw ProtocolError("Response \(frame.requestId) does not match a request in flight")
				}
				waiter.continuation.resume(returning: frame)
			}
		} catch {
			if state.withLock({ $0.connected }) {
				logger.error("Dropping the connection: \(String(describing: error), privacy: .public)")
			}
			failEveryCall()
		}
		sending.withLock {
			state.withLock { state in
				state.closed = true
				close(descriptor)
			}
		}
	}

	private func failEveryCall() {
		let waiters = state.withLock { state in
			state.connected = false
			defer { state.waiting = [:] }
			return Array(state.waiting.values)
		}
		for waiter in waiters {
			waiter.continuation.resume(throwing: StatusError(status: EIO))
		}
	}

	private func send(_ data: Data) throws {
		try data.withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
			var sent = 0
			while sent < buffer.count {
				let result = Darwin.send(descriptor, buffer.baseAddress! + sent, buffer.count - sent, 0)
				if result < 0, errno == EINTR {
					continue
				}
				guard result > 0 else {
					throw ConnectionError.sending(errno)
				}
				sent += result
			}
		}
	}

	private func receive(_ count: Int) throws -> Data {
		var data = Data(count: count)
		try data.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
			var received = 0
			while received < count {
				let result = recv(descriptor, buffer.baseAddress! + received, count - received, 0)
				if result < 0, errno == EINTR {
					continue
				}
				guard result > 0 else {
					throw result == 0 ? ConnectionError.closedByServer : ConnectionError.receiving(errno)
				}
				received += result
			}
		}
		return data
	}
}
