/// Decides when FSKit's cache of a file is stale, because something else changed the file in the backing store, and who revokes it.
///
/// It keeps, for each file item handed out, a baseline of what FSKit's cache provably holds: the record of the reply that handed the item out, moved only by the fields that the volume's own changes set. Every other record of the file is compared with it. A difference in type, mode, size or modification time makes a revoke due. The access time is left out, since reading changes it.
///
/// The server stamps every record with a generation, and a higher one means a later state. A record it read under the node's data lock, which each of the volume's own changes holds while it applies and samples, shows exactly the changes with a generation up to its own. A record it sampled without that lock may show a change in progress, partly applied, so one that arrives while the volume's own changes are in flight is judged only if it is newer than all of them. `PROTOCOL.md` lists which responses are read under the data lock.
///
/// A due revoke is claimed for the item it concerns, at most once at a time, and a successful one leaves the item revoked until it is reclaimed. An item keeps its entry for as long as FSKit keeps it: a lookup that hands it out again leaves its claim and its own changes as they are, since FSKit may hand it out with the pages it cached.
///
/// A value without a lock of its own: the volume keeps it under its state lock.
public struct CacheValidity<Item: AnyObject> {
	/// A change the volume made to a file, with the record of its reply.
	public enum OwnChange {
		/// A `SETATTR`, with the bit set of the attributes it applied.
		case setattr(applied: UInt8, Attributes)
		/// A `WRITE` that ended at the given offset.
		case write(end: UInt64, Attributes)

		var record: Attributes {
			switch self {
			case let .setattr(_, record), let .write(_, record): record
			}
		}
	}

	/// How the server read a record.
	public enum Reading {
		/// Under the node's data lock, as for the responses that `PROTOCOL.md`'s generation note lists.
		case locked
		/// Under the entry's sampling lock alone, as for a lookup or a listing.
		case sampled
	}

	/// What an open or upgrade of an item may do.
	public enum Admission: Equatable {
		/// Grant a cache: what FSKit cached of the item is current.
		case grant
		/// Run the revoke that was due and is now the caller's, then refuse.
		case revoke
		/// Refuse: a revoke of the item runs or ran.
		case refuse
	}

	private enum Claim {
		case none
		case revoking
		case revoked
	}

	private struct Entry {
		let item: Item
		var baseline: Attributes
		var claim = Claim.none
		/// The volume's own changes in flight. Changes that overlap form a batch, which is applied to the baseline once none is in flight.
		var ownChanges = 0
		/// The fields the changes of the batch may set, as bits of `Messages`.
		var settable: UInt8 = 0
		/// The changes of the batch that have ended and succeeded.
		var changes: [OwnChange] = []
		/// The records read under the data lock while the batch is in flight, judged in full once it was applied.
		var waiting: [Attributes] = []
		/// The records sampled while the batch is in flight, judged once it was applied if they are newer than all its changes.
		var sampled: [Attributes] = []
	}

	private var entries: [UInt64: Entry] = [:]
	/// The nodes whose revoke is due and not claimed.
	private var due: Set<UInt64> = []

	public init() {}

	/// Notes the record of a reply that hands out an item: a lookup or a create. A file is tracked from its first such reply on. One that hands out a tracked item again is compared as a sampled record, since a lookup reads without the data lock.
	public mutating func handedOut(_ item: Item, _ record: Attributes) {
		if let entry = entries[record.nodeId], entry.item === item {
			compare(record, .sampled)
		} else if record.type == .file {
			entries[record.nodeId] = Entry(item: item, baseline: record)
			due.remove(record.nodeId)
		} else {
			entries.removeValue(forKey: record.nodeId)
			due.remove(record.nodeId)
		}
	}

	/// Compares a record of a tracked file with its baseline. While the volume's own changes are in flight, a record read under the data lock is compared at once in the fields none of them sets, and kept to be compared in full once they were applied. A sampled one is kept until then.
	public mutating func compare(_ record: Attributes, _ reading: Reading) {
		guard let entry = entries[record.nodeId] else {
			return
		}
		guard entry.ownChanges > 0 else {
			judge(record, entry.claim, entry.baseline)
			return
		}
		switch reading {
		case .locked:
			judge(record, entry.claim, entry.baseline, ignoring: entry.settable)
			entries[record.nodeId]?.waiting.append(record)
		case .sampled:
			entries[record.nodeId]?.sampled.append(record)
		}
	}

	/// Notes that a request for one of the volume's own changes to the file is about to be sent.
	///
	/// - Parameter fields: The fields it may set, as bits of `Messages`.
	public mutating func ownChangeBegan(_ nodeId: UInt64, setting fields: UInt8) {
		entries[nodeId]?.ownChanges += 1
		// changing the size moves the modification time
		entries[nodeId]?.settable |= fields & Messages.attributeSize != 0 ? fields | Messages.attributeModified : fields
	}

	/// Notes the end of one of the volume's own changes. While others are still in flight, judges its record at once in the fields none of them sets. Once none is in flight, applies the changes to the baseline in the server's order, whichever order their replies arrived in. Each record that waited, the changes' own included, is then judged against the baseline of its generation, and each sampled one against the final baseline.
	///
	/// - Parameter change: What the change applied, or `nil` for a request that failed.
	public mutating func ownChangeEnded(_ nodeId: UInt64, _ change: OwnChange?) {
		guard entries[nodeId]?.ownChanges ?? 0 > 0 else {
			return
		}
		entries[nodeId]?.ownChanges -= 1
		guard var entry = entries[nodeId] else {
			return
		}
		if let change {
			entry.changes.append(change)
			entries[nodeId] = entry
		}
		guard entry.ownChanges == 0 else {
			if let change {
				judge(change.record, entry.claim, entry.baseline, ignoring: entry.settable)
			}
			return
		}
		let changes = entry.changes.sorted { $0.record.generation < $1.record.generation }
		let records = (entry.waiting + changes.map(\.record)).sorted { $0.generation < $1.generation }
		var next = changes.startIndex
		for record in records {
			while next < changes.endIndex, changes[next].record.generation <= record.generation {
				entry.baseline = applied(changes[next], to: entry.baseline)
				next += 1
			}
			judge(record, entry.claim, entry.baseline)
		}
		// a sample older than the baseline, which may show a change of the batch partly applied, is ignored
		entry.sampled.forEach { judge($0, entry.claim, entry.baseline) }
		entry.settable = 0
		entry.changes = []
		entry.waiting = []
		entry.sampled = []
		entries[nodeId] = entry
	}

	/// Notes that the server no longer finds a tracked file.
	public mutating func missing(_ nodeId: UInt64) {
		guard let entry = entries[nodeId], entry.claim == .none else {
			return
		}
		due.insert(nodeId)
	}

	/// Claims every due revoke.
	///
	/// - Returns: The items to revoke, each of which `revokeEnded` has to be told about.
	public mutating func claimDue() -> [Item] {
		let claimed = due.compactMap { nodeId -> Item? in
			entries[nodeId]?.claim = .revoking
			return entries[nodeId]?.item
		}
		due = []
		return claimed
	}

	/// Decides an open or upgrade of an item. A due revoke of the item becomes the caller's.
	public mutating func admit(_ item: Item, nodeId: UInt64) -> Admission {
		guard let entry = entries[nodeId], entry.item === item else {
			return .grant
		}
		if due.remove(nodeId) != nil {
			entries[nodeId]?.claim = .revoking
			return .revoke
		}
		return entry.claim == .none ? .grant : .refuse
	}

	/// Notes the end of a revoke this item's claim started. A successful one leaves the item revoked, a failed one makes the revoke due again. The end of a revoke of an item that was reclaimed meanwhile changes nothing.
	public mutating func revokeEnded(_ item: Item, nodeId: UInt64, success: Bool) {
		guard let entry = entries[nodeId], entry.item === item, entry.claim == .revoking else {
			return
		}
		entries[nodeId]?.claim = success ? .revoked : .none
		if !success {
			due.insert(nodeId)
		}
	}

	/// Forgets an item that FSKit reclaimed.
	public mutating func reclaimed(_ nodeId: UInt64) {
		entries.removeValue(forKey: nodeId)
		due.remove(nodeId)
	}

	/// Makes a revoke due if the item is not claimed, and the record is not older than the baseline and differs from it in a field it is not told to ignore.
	///
	/// - Parameter fields: Bits of `Messages` for the fields to leave out.
	private mutating func judge(_ record: Attributes, _ claim: Claim, _ baseline: Attributes, ignoring fields: UInt8 = 0) {
		guard claim == .none, record.generation >= baseline.generation else {
			return
		}
		func differs(_ field: UInt8, _ differ: Bool) -> Bool {
			fields & field == 0 && differ
		}
		if record.type != baseline.type || differs(Messages.attributeMode, record.mode != baseline.mode) || differs(Messages.attributeSize, record.size != baseline.size) || differs(Messages.attributeModified, record.modified != baseline.modified) {
			due.insert(record.nodeId)
		}
	}

	private func applied(_ change: OwnChange, to baseline: Attributes) -> Attributes {
		var baseline = baseline
		let record = change.record
		switch change {
		case let .setattr(applied, _):
			if applied & Messages.attributeSize != 0 {
				baseline.size = record.size
				// changing the size moves the modification time
				baseline.modified = record.modified
			}
			if applied & Messages.attributeMode != 0 {
				baseline.mode = record.mode
			}
			if applied & Messages.attributeModified != 0 {
				baseline.modified = record.modified
			}
		case let .write(end, _):
			if end > baseline.size, record.size == end {
				baseline.size = end
			}
			baseline.modified = record.modified
		}
		baseline.generation = record.generation
		return baseline
	}
}
