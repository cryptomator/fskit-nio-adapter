/// Maps node ids to items, so that repeated lookups of one node return the same instance, as FSKit's reclaim accounting requires. It counts the lookups of each, which a `FORGET` hands back to the server.
///
/// A value without a lock of its own: the volume keeps it under the lock in which it replies with items and reclaims them.
public struct ItemTable<Item: AnyObject> {
	private let make: (UInt64) -> Item
	private var entries: [UInt64: (item: Item, lookups: UInt64)] = [:]

	/// - Parameter make: Makes the item for a node id that has none.
	public init(make: @escaping (UInt64) -> Item) {
		self.make = make
	}

	/// Returns the one instance for a node id and counts a lookup of it.
	public mutating func item(for nodeId: UInt64) -> Item {
		let item = entries[nodeId]?.item ?? make(nodeId)
		entries[nodeId] = (item, (entries[nodeId]?.lookups ?? 0) + 1)
		return item
	}

	/// Removes an item, unless a lookup after its reclaim has replaced it.
	///
	/// - Returns: The number of lookups of the removed item, or `nil` if it was not the one mapped to its node id.
	public mutating func removeIfCurrent(_ item: Item, nodeId: UInt64) -> UInt64? {
		guard let entry = entries[nodeId], entry.item === item else {
			return nil
		}
		entries.removeValue(forKey: nodeId)
		return entry.lookups
	}
}
