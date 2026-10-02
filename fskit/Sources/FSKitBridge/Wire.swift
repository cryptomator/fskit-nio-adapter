import Foundation

/// Thrown when a frame, message or manifest violates the bridge protocol. A connection that carried it must be closed.
public struct ProtocolError: Error, Equatable {

	public let message: String

	public init(_ message: String) {
		self.message = message
	}
}

/// Appends the protocol's primitives to a byte buffer. All integers are big-endian.
public struct ByteWriter {

	public private(set) var data = Data()

	public init() {
	}

	public mutating func write<T: FixedWidthInteger>(_ value: T) {
		withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
	}

	public mutating func write(_ value: Bool) {
		write(UInt8(value ? 1 : 0))
	}

	public mutating func write(_ value: String) {
		let bytes = Data(value.utf8)
		precondition(bytes.count <= Int(UInt16.max), "String exceeds \(UInt16.max) bytes")
		write(UInt16(bytes.count))
		data.append(bytes)
	}

	public mutating func write(_ bytes: Data) {
		data.append(bytes)
	}
}

/// Reads the protocol's primitives from a byte buffer, failing on a buffer that is too short.
public struct ByteReader {

	private let data: Data
	private var offset: Int

	public init(_ data: Data) {
		self.data = data
		self.offset = data.startIndex
	}

	public var remaining: Int {
		data.endIndex - offset
	}

	public mutating func read<T: FixedWidthInteger>(_ type: T.Type = T.self) throws -> T {
		let bytes = try readBytes(MemoryLayout<T>.size)
		return bytes.reduce(into: T.zero) { result, byte in
			result = (result << 8) | T(truncatingIfNeeded: byte)
		}
	}

	public mutating func readBool() throws -> Bool {
		switch try read(UInt8.self) {
		case 0: return false
		case 1: return true
		default: throw ProtocolError("Invalid boolean")
		}
	}

	public mutating func readString() throws -> String {
		let bytes = try readBytes(Int(try read(UInt16.self)))
		guard let string = String(validating: bytes, as: UTF8.self) else {
			throw ProtocolError("String is not valid UTF-8")
		}
		return string
	}

	public mutating func readBytes(_ count: Int) throws -> Data {
		guard count <= remaining else {
			throw ProtocolError("Field of \(count) bytes exceeds the remaining \(remaining)")
		}
		defer { offset += count }
		return data.subdata(in: offset..<(offset + count))
	}
}
