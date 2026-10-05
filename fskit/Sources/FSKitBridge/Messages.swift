import Foundation

/// The messages of the bridge protocol, as specified in `protocol/PROTOCOL.md`.
public enum Messages {
	public static let magic: UInt32 = 0x4653_4B4E
	public static let protocolVersion: UInt16 = 1
	public static let tokenLength = 32

	public static let rootNodeId: UInt64 = 2

	public static let statusInvalidCookie: Int32 = -1
	public static let unknownUsableBytes = UInt64.max

	public static let modeRead: UInt8 = 1
	public static let modeWrite: UInt8 = 2

	public static let attributeSize: UInt8 = 1
	public static let attributeMode: UInt8 = 2
	public static let attributeAccessed: UInt8 = 4
	public static let attributeModified: UInt8 = 8

	public static func frame<R: Request>(for request: R, requestId: UInt64) -> Frame {
		var control = ByteWriter()
		request.encode(control: &control)
		return Frame(kind: .request, opcode: R.opcode, requestId: requestId, control: control.data, payload: request.payload)
	}

	public static func frame(for response: some Message, opcode: Opcode, requestId: UInt64) -> Frame {
		var control = ByteWriter()
		control.write(Int32(0))
		response.encode(control: &control)
		return Frame(kind: .response, opcode: opcode, requestId: requestId, control: control.data, payload: response.payload)
	}

	public static func frame(forFailure status: Int32, opcode: Opcode, requestId: UInt64) -> Frame {
		var control = ByteWriter()
		control.write(status)
		return Frame(kind: .response, opcode: opcode, requestId: requestId, control: control.data, payload: Data())
	}

	public static func decodeRequest<R: Request>(_ type: R.Type = R.self, from frame: Frame) throws -> R {
		guard frame.kind == .request, frame.opcode == R.opcode else {
			throw ProtocolError("Not a \(R.opcode) request")
		}
		var control = ByteReader(frame.control)
		return try decode(control: &control, payload: frame.payload)
	}

	/// - Throws: `StatusError` if the response reports a failure, `ProtocolError` if it is malformed.
	public static func decodeResponse<M: Message>(_ type: M.Type = M.self, from frame: Frame) throws -> M {
		guard frame.kind == .response else {
			throw ProtocolError("Not a response")
		}
		var control = ByteReader(frame.control)
		let status = try control.read(Int32.self)
		guard status == 0 else {
			guard control.remaining == 0, frame.payload.isEmpty else {
				throw ProtocolError("Bytes left over after status \(status)")
			}
			throw StatusError(status: status)
		}
		return try decode(control: &control, payload: frame.payload)
	}

	private static func decode<M: Message>(control: inout ByteReader, payload: Data) throws -> M {
		let message = try M(control: &control, payload: payload)
		guard control.remaining == 0 else {
			throw ProtocolError("\(control.remaining) bytes left over in control section")
		}
		guard M.carriesPayload || payload.isEmpty else {
			throw ProtocolError("Unexpected payload of \(payload.count) bytes")
		}
		return message
	}
}

/// A response's non-zero status: a macOS errno value or `Messages.statusInvalidCookie`.
public struct StatusError: Error, Equatable {
	public let status: Int32

	public init(status: Int32) {
		self.status = status
	}
}

public protocol Message: Sendable {
	static var carriesPayload: Bool { get }

	init(control: inout ByteReader, payload: Data) throws

	func encode(control: inout ByteWriter)

	var payload: Data { get }
}

public extension Message {
	static var carriesPayload: Bool {
		false
	}

	func encode(control: inout ByteWriter) {}

	var payload: Data {
		Data()
	}
}

public protocol Request: Message {
	associatedtype Response: Message

	static var opcode: Opcode { get }
}

// MARK: - Shared records

public enum NodeType: UInt8, Sendable {
	case file = 1
	case directory = 2
	case symlink = 3

	init(control: inout ByteReader) throws {
		guard let type = try NodeType(rawValue: control.read()) else {
			throw ProtocolError("Unknown node type")
		}
		self = type
	}
}

public struct Timestamp: Equatable, Sendable {
	public var seconds: Int64
	public var nanos: UInt32

	public init(seconds: Int64, nanos: UInt32) {
		self.seconds = seconds
		self.nanos = nanos
	}

	init(control: inout ByteReader) throws {
		self.seconds = try control.read()
		self.nanos = try control.read()
	}

	func encode(control: inout ByteWriter) {
		control.write(seconds)
		control.write(nanos)
	}
}

public struct Attributes: Equatable, Sendable {
	public var type: NodeType
	public var mode: UInt16
	public var size: UInt64
	public var nodeId: UInt64
	public var parentId: UInt64
	public var modified: Timestamp
	public var accessed: Timestamp
	public var created: Timestamp

	public init(type: NodeType, mode: UInt16, size: UInt64, nodeId: UInt64, parentId: UInt64, modified: Timestamp, accessed: Timestamp, created: Timestamp) {
		self.type = type
		self.mode = mode
		self.size = size
		self.nodeId = nodeId
		self.parentId = parentId
		self.modified = modified
		self.accessed = accessed
		self.created = created
	}

	init(control: inout ByteReader) throws {
		self.type = try NodeType(control: &control)
		self.mode = try control.read()
		self.size = try control.read()
		self.nodeId = try control.read()
		self.parentId = try control.read()
		self.modified = try Timestamp(control: &control)
		self.accessed = try Timestamp(control: &control)
		self.created = try Timestamp(control: &control)
	}

	func encode(control: inout ByteWriter) {
		control.write(type.rawValue)
		control.write(mode)
		control.write(size)
		control.write(nodeId)
		control.write(parentId)
		modified.encode(control: &control)
		accessed.encode(control: &control)
		created.encode(control: &control)
	}
}

public struct DirectoryEntry: Equatable, Sendable {
	public var name: String
	public var type: NodeType
	public var nodeId: UInt64
	public var nextCookie: UInt64
	public var attributes: Attributes?

	public init(name: String, type: NodeType, nodeId: UInt64, nextCookie: UInt64, attributes: Attributes?) {
		self.name = name
		self.type = type
		self.nodeId = nodeId
		self.nextCookie = nextCookie
		self.attributes = attributes
	}

	init(control: inout ByteReader) throws {
		self.name = try control.readString()
		self.type = try NodeType(control: &control)
		self.nodeId = try control.read()
		self.nextCookie = try control.read()
		self.attributes = try control.readBool() ? Attributes(control: &control) : nil
	}

	func encode(control: inout ByteWriter) {
		control.write(name)
		control.write(type.rawValue)
		control.write(nodeId)
		control.write(nextCookie)
		control.write(attributes != nil)
		attributes?.encode(control: &control)
	}
}

// MARK: - HELLO

public struct HelloRequest: Request, Equatable {
	public typealias Response = HelloResponse
	public static let opcode = Opcode.hello

	public var magic: UInt32
	public var protocolVersion: UInt16
	public var token: Data

	public init(magic: UInt32, protocolVersion: UInt16, token: Data) {
		self.magic = magic
		self.protocolVersion = protocolVersion
		self.token = token
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.magic = try control.read()
		self.protocolVersion = try control.read()
		// what follows magic and version is not interpreted here, since a client speaking another version may lay it out differently
		self.token = try control.readBytes(control.remaining)
	}

	public func encode(control: inout ByteWriter) {
		control.write(magic)
		control.write(protocolVersion)
		control.write(token)
	}
}

public struct HelloResponse: Message, Equatable {
	public init() {}

	public init(control: inout ByteReader, payload: Data) throws {}
}

// MARK: - STATFS

public struct StatfsRequest: Request, Equatable {
	public typealias Response = StatfsResponse
	public static let opcode = Opcode.statfs

	public init() {}

	public init(control: inout ByteReader, payload: Data) throws {}
}

public struct StatfsResponse: Message, Equatable {
	public var totalBytes: UInt64
	public var usableBytes: UInt64

	public init(totalBytes: UInt64, usableBytes: UInt64) {
		self.totalBytes = totalBytes
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.totalBytes = try control.read()
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(totalBytes)
		control.write(usableBytes)
	}
}

// MARK: - LOOKUP

public struct LookupRequest: Request, Equatable {
	public typealias Response = LookupResponse
	public static let opcode = Opcode.lookup

	public var parentId: UInt64
	public var name: String

	public init(parentId: UInt64, name: String) {
		self.parentId = parentId
		self.name = name
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.parentId = try control.read()
		self.name = try control.readString()
	}

	public func encode(control: inout ByteWriter) {
		control.write(parentId)
		control.write(name)
	}
}

public struct LookupResponse: Message, Equatable {
	public var attributes: Attributes
	public var name: String

	public init(attributes: Attributes, name: String) {
		self.attributes = attributes
		self.name = name
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.attributes = try Attributes(control: &control)
		self.name = try control.readString()
	}

	public func encode(control: inout ByteWriter) {
		attributes.encode(control: &control)
		control.write(name)
	}
}

// MARK: - FORGET

public struct ForgetRequest: Request, Equatable {
	public typealias Response = ForgetResponse
	public static let opcode = Opcode.forget

	public var nodeId: UInt64

	public init(nodeId: UInt64) {
		self.nodeId = nodeId
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
	}
}

public struct ForgetResponse: Message, Equatable {
	public var usableBytes: UInt64

	public init(usableBytes: UInt64) {
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(usableBytes)
	}
}

// MARK: - GETATTR

public struct GetattrRequest: Request, Equatable {
	public typealias Response = GetattrResponse
	public static let opcode = Opcode.getattr

	public var nodeId: UInt64

	public init(nodeId: UInt64) {
		self.nodeId = nodeId
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
	}
}

public struct GetattrResponse: Message, Equatable {
	public var attributes: Attributes

	public init(attributes: Attributes) {
		self.attributes = attributes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.attributes = try Attributes(control: &control)
	}

	public func encode(control: inout ByteWriter) {
		attributes.encode(control: &control)
	}
}

// MARK: - SETATTR

public struct SetattrRequest: Request, Equatable {
	public typealias Response = SetattrResponse
	public static let opcode = Opcode.setattr

	public var nodeId: UInt64
	public var valid: UInt8
	public var size: UInt64
	public var mode: UInt16
	public var accessed: Timestamp
	public var modified: Timestamp

	public init(nodeId: UInt64, valid: UInt8, size: UInt64, mode: UInt16, accessed: Timestamp, modified: Timestamp) {
		self.nodeId = nodeId
		self.valid = valid
		self.size = size
		self.mode = mode
		self.accessed = accessed
		self.modified = modified
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
		self.valid = try control.read()
		self.size = try control.read()
		self.mode = try control.read()
		self.accessed = try Timestamp(control: &control)
		self.modified = try Timestamp(control: &control)
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
		control.write(valid)
		control.write(size)
		control.write(mode)
		accessed.encode(control: &control)
		modified.encode(control: &control)
	}
}

public struct SetattrResponse: Message, Equatable {
	public var applied: UInt8
	public var attributes: Attributes
	public var usableBytes: UInt64

	public init(applied: UInt8, attributes: Attributes, usableBytes: UInt64) {
		self.applied = applied
		self.attributes = attributes
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.applied = try control.read()
		self.attributes = try Attributes(control: &control)
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(applied)
		attributes.encode(control: &control)
		control.write(usableBytes)
	}
}

// MARK: - READDIR

public struct ReaddirRequest: Request, Equatable {
	public typealias Response = ReaddirResponse
	public static let opcode = Opcode.readdir

	public var nodeId: UInt64
	public var cookie: UInt64
	public var verifier: UInt64
	public var wantAttributes: Bool

	public init(nodeId: UInt64, cookie: UInt64, verifier: UInt64, wantAttributes: Bool) {
		self.nodeId = nodeId
		self.cookie = cookie
		self.verifier = verifier
		self.wantAttributes = wantAttributes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
		self.cookie = try control.read()
		self.verifier = try control.read()
		self.wantAttributes = try control.readBool()
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
		control.write(cookie)
		control.write(verifier)
		control.write(wantAttributes)
	}
}

public struct ReaddirResponse: Message, Equatable {
	public var verifier: UInt64
	public var more: Bool
	public var entries: [DirectoryEntry]

	public init(verifier: UInt64, more: Bool, entries: [DirectoryEntry]) {
		self.verifier = verifier
		self.more = more
		self.entries = entries
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.verifier = try control.read()
		self.more = try control.readBool()
		let count = try Int(control.read(UInt16.self))
		self.entries = []
		for _ in 0 ..< count {
			try entries.append(DirectoryEntry(control: &control))
		}
	}

	public func encode(control: inout ByteWriter) {
		control.write(verifier)
		control.write(more)
		control.write(UInt16(entries.count))
		entries.forEach { $0.encode(control: &control) }
	}
}

// MARK: - CREATE

public struct CreateRequest: Request, Equatable {
	public typealias Response = CreateResponse
	public static let opcode = Opcode.create

	public var parentId: UInt64
	public var name: String
	public var type: NodeType
	public var mode: UInt16

	public init(parentId: UInt64, name: String, type: NodeType, mode: UInt16) {
		self.parentId = parentId
		self.name = name
		self.type = type
		self.mode = mode
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.parentId = try control.read()
		self.name = try control.readString()
		self.type = try NodeType(control: &control)
		self.mode = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(parentId)
		control.write(name)
		control.write(type.rawValue)
		control.write(mode)
	}
}

public struct CreateResponse: Message, Equatable {
	public var attributes: Attributes
	public var name: String
	public var directoryAttributes: Attributes
	public var usableBytes: UInt64

	public init(attributes: Attributes, name: String, directoryAttributes: Attributes, usableBytes: UInt64) {
		self.attributes = attributes
		self.name = name
		self.directoryAttributes = directoryAttributes
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.attributes = try Attributes(control: &control)
		self.name = try control.readString()
		self.directoryAttributes = try Attributes(control: &control)
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		attributes.encode(control: &control)
		control.write(name)
		directoryAttributes.encode(control: &control)
		control.write(usableBytes)
	}
}

// MARK: - REMOVE

public struct RemoveRequest: Request, Equatable {
	public typealias Response = RemoveResponse
	public static let opcode = Opcode.remove

	public var nodeId: UInt64
	public var parentId: UInt64

	public init(nodeId: UInt64, parentId: UInt64) {
		self.nodeId = nodeId
		self.parentId = parentId
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
		self.parentId = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
		control.write(parentId)
	}
}

public struct RemoveResponse: Message, Equatable {
	public var attributes: Attributes
	public var directoryAttributes: Attributes
	public var usableBytes: UInt64

	public init(attributes: Attributes, directoryAttributes: Attributes, usableBytes: UInt64) {
		self.attributes = attributes
		self.directoryAttributes = directoryAttributes
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.attributes = try Attributes(control: &control)
		self.directoryAttributes = try Attributes(control: &control)
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		attributes.encode(control: &control)
		directoryAttributes.encode(control: &control)
		control.write(usableBytes)
	}
}

// MARK: - RENAME

public struct RenameRequest: Request, Equatable {
	public typealias Response = RenameResponse
	public static let opcode = Opcode.rename

	public var nodeId: UInt64
	public var sourceParentId: UInt64
	public var destinationParentId: UInt64
	public var destinationName: String

	public init(nodeId: UInt64, sourceParentId: UInt64, destinationParentId: UInt64, destinationName: String) {
		self.nodeId = nodeId
		self.sourceParentId = sourceParentId
		self.destinationParentId = destinationParentId
		self.destinationName = destinationName
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
		self.sourceParentId = try control.read()
		self.destinationParentId = try control.read()
		self.destinationName = try control.readString()
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
		control.write(sourceParentId)
		control.write(destinationParentId)
		control.write(destinationName)
	}
}

public struct RenameResponse: Message, Equatable {
	public var name: String
	public var attributes: Attributes
	public var sourceDirectoryAttributes: Attributes
	public var destinationDirectoryAttributes: Attributes
	public var replacedAttributes: Attributes?
	public var usableBytes: UInt64

	public init(name: String, attributes: Attributes, sourceDirectoryAttributes: Attributes, destinationDirectoryAttributes: Attributes, replacedAttributes: Attributes?, usableBytes: UInt64) {
		self.name = name
		self.attributes = attributes
		self.sourceDirectoryAttributes = sourceDirectoryAttributes
		self.destinationDirectoryAttributes = destinationDirectoryAttributes
		self.replacedAttributes = replacedAttributes
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.name = try control.readString()
		self.attributes = try Attributes(control: &control)
		self.sourceDirectoryAttributes = try Attributes(control: &control)
		self.destinationDirectoryAttributes = try Attributes(control: &control)
		self.replacedAttributes = try control.readBool() ? Attributes(control: &control) : nil
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(name)
		attributes.encode(control: &control)
		sourceDirectoryAttributes.encode(control: &control)
		destinationDirectoryAttributes.encode(control: &control)
		control.write(replacedAttributes != nil)
		replacedAttributes?.encode(control: &control)
		control.write(usableBytes)
	}
}

// MARK: - OPEN

public struct OpenRequest: Request, Equatable {
	public typealias Response = OpenResponse
	public static let opcode = Opcode.open

	public var nodeId: UInt64
	public var modes: UInt8

	public init(nodeId: UInt64, modes: UInt8) {
		self.nodeId = nodeId
		self.modes = modes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
		self.modes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
		control.write(modes)
	}
}

public struct OpenResponse: Message, Equatable {
	public init() {}

	public init(control: inout ByteReader, payload: Data) throws {}
}

// MARK: - CLOSE

public struct CloseRequest: Request, Equatable {
	public typealias Response = CloseResponse
	public static let opcode = Opcode.close

	public var nodeId: UInt64
	public var keptModes: UInt8

	public init(nodeId: UInt64, keptModes: UInt8) {
		self.nodeId = nodeId
		self.keptModes = keptModes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
		self.keptModes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
		control.write(keptModes)
	}
}

public struct CloseResponse: Message, Equatable {
	public var usableBytes: UInt64

	public init(usableBytes: UInt64) {
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(usableBytes)
	}
}

// MARK: - READ

public struct ReadRequest: Request, Equatable {
	public typealias Response = ReadResponse
	public static let opcode = Opcode.read

	public var nodeId: UInt64
	public var offset: UInt64
	public var length: UInt32

	public init(nodeId: UInt64, offset: UInt64, length: UInt32) {
		self.nodeId = nodeId
		self.offset = offset
		self.length = length
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
		self.offset = try control.read()
		self.length = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
		control.write(offset)
		control.write(length)
	}
}

public struct ReadResponse: Message, Equatable {
	public static let carriesPayload = true

	public var attributes: Attributes
	public var data: Data

	public init(attributes: Attributes, data: Data) {
		self.attributes = attributes
		self.data = data
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.attributes = try Attributes(control: &control)
		self.data = payload
	}

	public func encode(control: inout ByteWriter) {
		attributes.encode(control: &control)
	}

	public var payload: Data {
		data
	}
}

// MARK: - WRITE

public struct WriteRequest: Request, Equatable {
	public typealias Response = WriteResponse
	public static let opcode = Opcode.write
	public static let carriesPayload = true

	public var nodeId: UInt64
	public var offset: UInt64
	public var data: Data

	public init(nodeId: UInt64, offset: UInt64, data: Data) {
		self.nodeId = nodeId
		self.offset = offset
		self.data = data
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.nodeId = try control.read()
		self.offset = try control.read()
		self.data = payload
	}

	public func encode(control: inout ByteWriter) {
		control.write(nodeId)
		control.write(offset)
	}

	public var payload: Data {
		data
	}
}

public struct WriteResponse: Message, Equatable {
	public var written: UInt32
	public var attributes: Attributes
	public var usableBytes: UInt64

	public init(written: UInt32, attributes: Attributes, usableBytes: UInt64) {
		self.written = written
		self.attributes = attributes
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.written = try control.read()
		self.attributes = try Attributes(control: &control)
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(written)
		attributes.encode(control: &control)
		control.write(usableBytes)
	}
}

// MARK: - SYNC

public struct SyncRequest: Request, Equatable {
	public typealias Response = SyncResponse
	public static let opcode = Opcode.sync

	public init() {}

	public init(control: inout ByteReader, payload: Data) throws {}
}

public struct SyncResponse: Message, Equatable {
	public var usableBytes: UInt64

	public init(usableBytes: UInt64) {
		self.usableBytes = usableBytes
	}

	public init(control: inout ByteReader, payload: Data) throws {
		self.usableBytes = try control.read()
	}

	public func encode(control: inout ByteWriter) {
		control.write(usableBytes)
	}
}
