import Foundation

/// Tells the extension where to find the server of a mount.
public struct Manifest: Equatable, Sendable {
	public static let fileName = "manifest"

	public var protocolVersion: UInt16
	public var port: UInt16
	public var token: Data
	public var volumeName: String

	public init(protocolVersion: UInt16, port: UInt16, token: Data, volumeName: String) {
		self.protocolVersion = protocolVersion
		self.port = port
		self.token = token
		self.volumeName = volumeName
	}

	public init(decoding data: Data) throws {
		var reader = ByteReader(data)
		self.protocolVersion = try reader.read()
		guard protocolVersion == Messages.protocolVersion else {
			throw ProtocolError("Unsupported protocol version \(protocolVersion)")
		}
		self.port = try reader.read()
		self.token = try reader.readBytes(Messages.tokenLength)
		self.volumeName = try reader.readString()
		guard reader.remaining == 0 else {
			throw ProtocolError("\(reader.remaining) bytes left over in manifest")
		}
	}

	public func encode() -> Data {
		var writer = ByteWriter()
		writer.write(protocolVersion)
		writer.write(port)
		writer.write(token)
		writer.write(volumeName)
		return writer.data
	}

	/// Reads the manifest from the directory that was passed to `mount`.
	public static func read(from directory: URL) throws -> Manifest {
		try Manifest(decoding: Data(contentsOf: directory.appendingPathComponent(fileName)))
	}
}
