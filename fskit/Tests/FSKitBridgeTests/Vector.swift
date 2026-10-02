import Foundation
import Testing

/// An example from `protocol/vectors`: the decoded field values and the encoded bytes.
struct Vector: Sendable, CustomTestStringConvertible {

	private static let directory = URL(fileURLWithPath: #filePath)
		.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
		.appendingPathComponent("protocol/vectors")
	private static let hexSeparator = "hex:"

	let name: String
	let fields: [String]
	let bytes: Data

	var testDescription: String {
		name
	}

	static func load(_ name: String) throws -> Vector {
		let lines = try String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)
			.split(separator: "\n").map(String.init).filter { !$0.hasPrefix("#") }
		let separator = try #require(lines.firstIndex(of: hexSeparator))
		return Vector(name: name, fields: Array(lines[..<separator]), bytes: try #require(Data(hex: lines[(separator + 1)...].joined())))
	}

	/// All examples that hold a message, i.e. all but the bare frame and the manifest.
	static let messages: [Vector] = {
		let names = try! FileManager.default.contentsOfDirectory(atPath: directory.path)
		return names.filter { $0 != "frame.txt" && $0 != "manifest.txt" }.sorted().map { try! load($0) }
	}()

	/// Lists the fields of a value the way the examples do.
	static func describe(_ value: Any) -> [String] {
		var fields: [String] = []
		describe(value, name: "", into: &fields)
		return fields
	}

	private static func describe(_ value: Any, name: String, into fields: inout [String]) {
		let mirror = Mirror(reflecting: value)
		switch value {
		case let data as Data:
			fields.append("\(name) = \(data.map { String(format: "%02x", $0) }.joined())")
		case is String, is Bool, is any FixedWidthInteger:
			fields.append("\(name) = \(value)")
		default:
			switch mirror.displayStyle {
			case .optional:
				// an absent optional record has no lines
				if let wrapped = mirror.children.first {
					describe(wrapped.value, name: name, into: &fields)
				}
			case .struct:
				for child in mirror.children {
					describe(child.value, name: name.isEmpty ? child.label! : "\(name).\(child.label!)", into: &fields)
				}
			case .collection:
				for (index, child) in mirror.children.enumerated() {
					describe(child.value, name: "\(name)[\(index)]", into: &fields)
				}
			default:
				fields.append("\(name) = \(value)")
			}
		}
	}
}

extension Data {

	init?(hex: String) {
		let digits = Array(hex.filter { !$0.isWhitespace })
		guard digits.count % 2 == 0 else {
			return nil
		}
		var bytes: [UInt8] = []
		for index in stride(from: 0, to: digits.count, by: 2) {
			guard let byte = UInt8(String(digits[index...(index + 1)]), radix: 16) else {
				return nil
			}
			bytes.append(byte)
		}
		self.init(bytes)
	}

	/// Bytes that differ from one payload-sized stretch to the next, so that a misplaced payload shows.
	init(testContentOfLength count: Int) {
		self.init((0..<count).map { UInt8(truncatingIfNeeded: $0 ^ ($0 >> 8) ^ ($0 >> 16)) })
	}

	/// Hands out this data in the pieces a frame decoder asks for.
	func reader() -> (Int) throws -> Data {
		var offset = startIndex
		return { count in
			guard offset + count <= endIndex else {
				throw POSIXError(.EIO)
			}
			defer { offset += count }
			return subdata(in: offset..<(offset + count))
		}
	}
}
