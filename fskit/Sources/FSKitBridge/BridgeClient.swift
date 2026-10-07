import Foundation
import os

/// Connects to the server of one mount and exchanges one request for one response at a time.
///
/// Calls block the calling thread and must not overlap; the extension makes them from the volume's serial queue. `hasUnsyncedChanges` and `nextRequestId` rely on that.
public final class BridgeClient {
	private let logger = Logger(subsystem: "org.cryptomator.fskit", category: "BridgeClient")
	/// Why the connection was lost, for the log.
	private enum ConnectionError: Error {
		case sending(Int32)
		case receiving(Int32)
		case closedByServer
	}

	private var descriptor: Int32
	/// Raised for every request sent, also for each part of a split read or write, so a value that has not changed means that nothing was sent in between.
	public private(set) var nextRequestId: UInt64 = 1

	/// Whether a `WRITE`, `SETATTR` or `CREATE` was sent since the last `SYNC` that succeeded. Only these leave data in a channel of the server that a `SYNC` forces, see `protocol/PROTOCOL.md`.
	///
	/// One flag suffices because calls do not overlap, so nothing can be sent between a `SYNC` and its response.
	public private(set) var hasUnsyncedChanges = false

	/// Connects to the loopback port named in the manifest and performs the handshake.
	public init(manifest: Manifest) throws {
		self.descriptor = socket(AF_INET, SOCK_STREAM, 0)
		guard descriptor >= 0 else {
			throw StatusError(status: errno)
		}
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
			_ = try request(HelloRequest(magic: Messages.magic, protocolVersion: Messages.protocolVersion, token: manifest.token))
		} catch {
			disconnect()
			throw error
		}
	}

	deinit {
		disconnect()
	}

	public func disconnect() {
		if descriptor >= 0 {
			close(descriptor)
			descriptor = -1
		}
	}

	/// Sends a request and waits for its response.
	///
	/// - Throws: `StatusError` with the status the server reported, which leaves the connection usable. `StatusError` with `EIO` if the connection is or gets lost or the response does not match the request. After such a loss or mismatch, every call fails with `EIO`.
	public func request<R: Request>(_ request: R) throws -> R.Response {
		let requestId = nextRequestId
		nextRequestId += 1
		if R.opcode == .write || R.opcode == .setattr || R.opcode == .create {
			hasUnsyncedChanges = true
		}
		do {
			try send(FrameCodec.encode(Messages.frame(for: request, requestId: requestId)))
			let frame = try FrameCodec.decode(readingFrom: receive)
			guard frame.kind == .response, frame.opcode == R.opcode, frame.requestId == requestId else {
				throw ProtocolError("Response does not match request \(requestId)")
			}
			let response: R.Response = try Messages.decodeResponse(from: frame)
			if R.opcode == .sync {
				hasUnsyncedChanges = false
			}
			return response
		} catch let status as StatusError {
			throw status
		} catch {
			if descriptor >= 0 {
				logger.error("Dropping the connection after request \(requestId) (\(String(describing: R.opcode), privacy: .public)): \(String(describing: error), privacy: .public)")
			}
			disconnect()
			throw StatusError(status: EIO)
		}
	}

	/// Reads up to `length` bytes, split into as many requests as the payload limit requires.
	public func read(nodeId: UInt64, offset: UInt64, length: Int) throws -> ReadResponse {
		var data = Data()
		var attributes: Attributes
		repeat {
			let requested = min(length - data.count, FrameCodec.maxPayloadLength)
			let response = try request(ReadRequest(nodeId: nodeId, offset: offset + UInt64(data.count), length: UInt32(requested)))
			attributes = response.attributes
			data.append(response.data)
			if response.data.count < requested {
				break // end of file
			}
		} while data.count < length
		return ReadResponse(attributes: attributes, data: data)
	}

	/// Writes `data`, split into as many requests as the payload limit requires, until all of it is written or the server accepts nothing more. The response's `written` is the total.
	public func write(nodeId: UInt64, offset: UInt64, data: Data) throws -> WriteResponse {
		var written = 0
		var response: WriteResponse
		repeat {
			let chunk = data.dropFirst(written).prefix(FrameCodec.maxPayloadLength)
			response = try request(WriteRequest(nodeId: nodeId, offset: offset + UInt64(written), data: Data(chunk)))
			written += Int(response.written)
		} while written < data.count && response.written > 0
		response.written = UInt32(written)
		return response
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
