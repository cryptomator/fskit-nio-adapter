import Foundation

public enum FrameCodec {

	public static let maxControlLength = 64 * 1024
	public static let maxPayloadLength = 1024 * 1024
	private static let headerLength = 1 + 2 + 8 + 4 // kind, opcode, request id, control length

	public static func encode(_ frame: Frame) throws -> Data {
		guard frame.control.count <= maxControlLength, frame.payload.count <= maxPayloadLength else {
			throw ProtocolError("Frame exceeds limits: control \(frame.control.count), payload \(frame.payload.count)")
		}
		var writer = ByteWriter()
		writer.write(UInt32(headerLength + frame.control.count + frame.payload.count))
		writer.write(frame.kind.rawValue)
		writer.write(frame.opcode.rawValue)
		writer.write(frame.requestId)
		writer.write(UInt32(frame.control.count))
		writer.write(frame.control)
		writer.write(frame.payload)
		return writer.data
	}

	/// Reads one frame.
	///
	/// - Parameter read: Returns exactly the requested number of bytes or throws.
	/// - Throws: `ProtocolError` if the frame violates the limits or names an unknown kind or opcode, or whatever `read` throws.
	public static func decode(readingFrom read: (Int) throws -> Data) throws -> Frame {
		var lengthReader = ByteReader(try read(4))
		let length = Int(try lengthReader.read(UInt32.self))
		guard length >= headerLength, length <= headerLength + maxControlLength + maxPayloadLength else {
			throw ProtocolError("Invalid frame length \(length)")
		}
		var header = ByteReader(try read(headerLength))
		guard let kind = Frame.Kind(rawValue: try header.read()) else {
			throw ProtocolError("Unknown frame kind")
		}
		guard let opcode = Opcode(rawValue: try header.read()) else {
			throw ProtocolError("Unknown opcode")
		}
		let requestId = try header.read(UInt64.self)
		let controlLength = Int(try header.read(UInt32.self))
		let payloadLength = length - headerLength - controlLength
		guard controlLength <= maxControlLength, payloadLength >= 0, payloadLength <= maxPayloadLength else {
			throw ProtocolError("Invalid control length \(controlLength) in frame of length \(length)")
		}
		return Frame(kind: kind, opcode: opcode, requestId: requestId, control: try read(controlLength), payload: try read(payloadLength))
	}
}
