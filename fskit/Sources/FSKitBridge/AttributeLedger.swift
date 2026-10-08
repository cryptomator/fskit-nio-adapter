/// The newest attributes of each item and the newest free space a volume has seen, by the server's generation.
///
/// Responses to overlapping requests may arrive and be handled in any order. FSKit takes the attributes and the free space populated last for the newest, so a handler populates the record `newest` returns, which is never older than one populated before.
///
/// A record is kept as long as a handler may still carry an older one: each handler takes a ticket when FSKit calls it, before it sends its request, and returns it once it has populated, also when it fails. A record is dropped once every handler that began before it was kept is done. A handler that begins later sends its request after the record's response arrived, so the server draws its generations later.
///
/// A value without a lock of its own: the volume keeps it under the lock in which it populates FSKit's objects.
public struct AttributeLedger: Sendable {
	public struct Ticket: Sendable {
		fileprivate let number: UInt64
	}

	private struct Kept: Sendable {
		var attributes: Attributes
		/// The ticket the next handler to begin gets: every handler with a lower one began before the record was kept.
		var nextTicket: UInt64
	}

	private var attributes: [UInt64: Kept] = [:]
	// one record, so it is kept for good
	private var freeSpace: FreeSpace?
	private var nextTicket: UInt64 = 0
	private var outstanding: Set<UInt64> = []

	public init() {}

	/// Whether no handler is between `begin` and `finish`.
	public var isIdle: Bool {
		outstanding.isEmpty
	}

	public mutating func begin() -> Ticket {
		defer { nextTicket += 1 }
		outstanding.insert(nextTicket)
		return Ticket(number: nextTicket)
	}

	public mutating func finish(_ ticket: Ticket) {
		outstanding.remove(ticket.number)
		let oldestOutstanding = outstanding.min() ?? nextTicket
		// only the end of the oldest handler can let records go
		guard ticket.number < oldestOutstanding else {
			return
		}
		attributes = attributes.filter { $0.value.nextTicket > oldestOutstanding }
	}

	/// - Returns: Whichever of the received record and the kept one of the same item has the higher generation, which is kept from then on.
	public mutating func newest(_ received: Attributes) -> Attributes {
		if let kept = attributes[received.nodeId], kept.attributes.generation > received.generation {
			return kept.attributes
		}
		attributes[received.nodeId] = Kept(attributes: received, nextTicket: nextTicket)
		return received
	}

	/// - Returns: The received sample's usable space if it is not older than the kept one, which it then replaces, or `nil` if it is older or reports no value.
	public mutating func newest(_ received: FreeSpace) -> UInt64? {
		guard received.usableBytes != Messages.unknownUsableBytes, freeSpace.map({ $0.generation <= received.generation }) ?? true else {
			return nil
		}
		freeSpace = received
		return received.usableBytes
	}
}
