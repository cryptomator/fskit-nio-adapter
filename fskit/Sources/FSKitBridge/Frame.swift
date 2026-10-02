import Foundation

public enum Opcode: UInt16, CaseIterable, Sendable {
	case hello = 1
	case statfs = 2
	case lookup = 3
	case forget = 4
	case getattr = 5
	case setattr = 6
	case readdir = 7
	case create = 8
	case remove = 9
	case rename = 10
	case open = 11
	case close = 12
	case read = 13
	case write = 14
	case sync = 15
}

public struct Frame: Equatable, Sendable {

	public enum Kind: UInt8, Sendable {
		case request = 0
		case response = 1
	}

	public var kind: Kind
	public var opcode: Opcode
	public var requestId: UInt64
	public var control: Data
	public var payload: Data

	public init(kind: Kind, opcode: Opcode, requestId: UInt64, control: Data, payload: Data) {
		self.kind = kind
		self.opcode = opcode
		self.requestId = requestId
		self.control = control
		self.payload = payload
	}
}
