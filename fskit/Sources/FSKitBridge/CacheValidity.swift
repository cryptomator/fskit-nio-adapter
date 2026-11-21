/// Decides when FSKit's cache of a file or directory is stale, because something else changed it in the backing store, and who drops that cache.
///
/// It keeps, for each file and directory item handed out, a baseline of what FSKit's cache provably holds. It starts as the record FSKit was handed with the item, the newest one at the time of the reply that handed it out, and moves by the fields that the volume's own changes set. Every other record of the item is compared with it. A difference in type, mode, size or modification time makes the item's action due: a revoke for a file, an invalidation of the names cached in it for a directory. The access time is left out, since reading changes it.
///
/// The server stamps every record with a generation, and a higher one means a later state. A record it read under the node's data lock, which each of the volume's own changes holds while it applies and samples, shows exactly the changes with a generation up to its own. For a directory, such a record also excludes the creates, links, removals and renames in it. A record it sampled without that lock may show a change in progress, partly applied, so one that arrives while the volume's own changes are in flight is judged only if it is newer than all of them. `PROTOCOL.md` lists which responses are read under the data lock.
///
/// A due revoke is claimed for the item it concerns, at most once at a time, and a successful one leaves the item revoked until it is reclaimed. An item keeps its entry for as long as FSKit keeps it: a lookup that hands it out again leaves its claim and its own changes as they are, since FSKit may hand it out with the pages it cached.
///
/// FSKit does not reclaim a directory it was told to invalidate: it looks the directory up again and keeps the item. So a directory has no claim, its opens are always granted, and every later difference makes another invalidation due. Since macOS fetches the directory anew after an invalidation, a fully judged record that differs moves the baseline to the newest record judged in full so far. A record that matches leaves the baseline as it is, so that an older differing record that arrives after it is still judged.
///
/// A value without a lock of its own: the volume keeps it under its state lock.
public struct CacheValidity<Item: AnyObject> {
	/// A change the volume made to a file or directory, with the record of its reply.
	public enum OwnChange {
		/// A `SETATTR`, with the bit set of the attributes it applied.
		case setattr(applied: UInt8, Attributes)
		/// A `WRITE` that ended at the given offset.
		case write(end: UInt64, Attributes)
		/// A create, link, removal or rename in a directory, with the directory's record.
		case entries(Attributes)

		var record: Attributes {
			switch self {
			case let .setattr(_, record), let .write(_, record), let .entries(record): record
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

	/// What a claim asks of the volume.
	public enum Action: Equatable, Sendable {
		/// Drop what FSKit cached of a file, which it then reclaims. `revokeEnded` has to be told about its end.
		case revoke
		/// Drop the names FSKit cached in a directory. `invalidationEnded` has to be told about its end.
		case invalidate
	}

	/// An item whose cache the volume has to drop.
	public struct Claimed {
		public let item: Item
		public let action: Action
	}

	private enum Claim {
		case none
		case revoking
		case revoked
	}

	private struct Entry {
		let item: Item
		/// Whether the item was handed out as a directory, which decides how it is judged and what a claim does until FSKit reclaims it.
		let isDirectory: Bool
		var baseline: Attributes
		/// The newest record judged in full, which a directory's baseline moves to once a record differs.
		var latest: Attributes
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

		init(item: Item, isDirectory: Bool, baseline: Attributes) {
			self.item = item
			self.isDirectory = isDirectory
			self.baseline = baseline
			self.latest = baseline
		}
	}

	private var entries: [UInt64: Entry] = [:]
	/// The nodes whose action is due and not claimed.
	private var due: Set<UInt64> = []

	public init() {}

	/// Notes the record of a reply that hands out an item: a lookup or a create. A file or directory is tracked from its first such reply on, with what FSKit is handed as its baseline. One that hands out a tracked item again is compared as a sampled record, since a lookup reads without the data lock.
	///
	/// - Parameters:
	///   - record: The record of the reply.
	///   - newest: What FSKit is handed: the newest record of the item, which may have arrived after the reply's. `nil` for the reply's own.
	public mutating func handedOut(_ item: Item, _ record: Attributes, newest: Attributes? = nil) {
		let newest = newest ?? record
		if let entry = entries[record.nodeId], entry.item === item {
			// a newer record that matches may hide one of the reply that differs
			compare(record, .sampled)
		} else if newest.type == .file || newest.type == .directory {
			entries[record.nodeId] = Entry(item: item, isDirectory: newest.type == .directory, baseline: newest)
			due.remove(record.nodeId)
		} else {
			entries.removeValue(forKey: record.nodeId)
			due.remove(record.nodeId)
		}
	}

	/// Compares a record of a tracked item with its baseline. While the volume's own changes are in flight, a record read under the data lock is compared at once in the fields none of them sets, and kept to be compared in full once they were applied. A sampled one is kept until then.
	public mutating func compare(_ record: Attributes, _ reading: Reading) {
		// taken out while it changes, so that appending to its records does not copy them
		guard var entry = entries.removeValue(forKey: record.nodeId) else {
			return
		}
		defer { entries[record.nodeId] = entry }
		if entry.ownChanges == 0 {
			judge(record, &entry)
		} else {
			switch reading {
			case .locked:
				let settable = entry.settable
				judge(record, &entry, ignoring: settable)
				entry.waiting.append(record)
			case .sampled:
				entry.sampled.append(record)
			}
		}
	}

	/// Notes that a request for one of the volume's own changes to the item is about to be sent.
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
		guard var entry = entries.removeValue(forKey: nodeId) else {
			return
		}
		defer { entries[nodeId] = entry }
		guard entry.ownChanges > 0 else {
			return
		}
		entry.ownChanges -= 1
		if let change {
			entry.changes.append(change)
		}
		guard entry.ownChanges == 0 else {
			if let change {
				let settable = entry.settable
				judge(change.record, &entry, ignoring: settable)
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
			judge(record, &entry)
		}
		// a sample older than the baseline, which may show a change of the batch partly applied, is ignored
		for record in entry.sampled {
			judge(record, &entry)
		}
		entry.settable = 0
		entry.changes = []
		entry.waiting = []
		entry.sampled = []
	}

	/// Notes that the server no longer finds a tracked item.
	public mutating func missing(_ nodeId: UInt64) {
		guard let entry = entries[nodeId], entry.claim == .none else {
			return
		}
		due.insert(nodeId)
	}

	/// Claims every due action.
	public mutating func claimDue() -> [Claimed] {
		let claimed = due.compactMap { nodeId -> Claimed? in
			guard let entry = entries[nodeId] else {
				return nil
			}
			guard !entry.isDirectory else {
				return Claimed(item: entry.item, action: .invalidate)
			}
			entries[nodeId]?.claim = .revoking
			return Claimed(item: entry.item, action: .revoke)
		}
		due = []
		return claimed
	}

	/// Decides an open or upgrade of an item. A due revoke of a file becomes the caller's. A directory is always granted, and its due invalidation stays to be claimed.
	public mutating func admit(_ item: Item, nodeId: UInt64) -> Admission {
		guard let entry = entries[nodeId], entry.item === item, !entry.isDirectory else {
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

	/// Notes the end of an invalidation of a directory. A failed one is due again, and a successful one changes nothing.
	public mutating func invalidationEnded(_ item: Item, nodeId: UInt64, success: Bool) {
		guard !success, let entry = entries[nodeId], entry.item === item else {
			return
		}
		due.insert(nodeId)
	}

	/// Forgets an item that FSKit reclaimed.
	public mutating func reclaimed(_ nodeId: UInt64) {
		entries.removeValue(forKey: nodeId)
		due.remove(nodeId)
	}

	/// Makes the entry's action due if the record is not older than the baseline and differs from it in a field it is not told to ignore, unless a revoke of the file is claimed. When a record judged in full differs, a directory's baseline moves to the newest record judged in full.
	///
	/// - Parameter fields: Bits of `Messages` for the fields to leave out.
	private mutating func judge(_ record: Attributes, _ entry: inout Entry, ignoring fields: UInt8 = 0) {
		guard entry.claim == .none, record.generation >= entry.baseline.generation else {
			return
		}
		if fields == 0, record.generation > entry.latest.generation {
			entry.latest = record
		}
		let baseline = entry.baseline
		func differs(_ field: UInt8, _ differ: Bool) -> Bool {
			fields & field == 0 && differ
		}
		guard record.type != baseline.type || differs(Messages.attributeMode, record.mode != baseline.mode) || differs(Messages.attributeSize, record.size != baseline.size) || differs(Messages.attributeModified, record.modified != baseline.modified) else {
			return
		}
		due.insert(record.nodeId)
		// macOS fetches the directory anew once it is invalidated
		if entry.isDirectory, fields == 0 {
			entry.baseline = entry.latest
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
		case .entries:
			baseline.size = record.size
			baseline.modified = record.modified
		}
		baseline.generation = record.generation
		return baseline
	}
}

extension CacheValidity.Claimed: Sendable where Item: Sendable {}
