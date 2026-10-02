import FSKit

/// Maps node ids to items, so that repeated lookups of one node return the same instance, as FSKit's reclaim accounting requires.
///
/// Confined to the volume's queue.
final class ItemRegistry {

	private var items: [UInt64: BridgeItem] = [:]

	func item(for nodeId: UInt64) -> BridgeItem {
		if let item = items[nodeId] {
			return item
		}
		let item = BridgeItem(nodeId: nodeId)
		items[nodeId] = item
		return item
	}

	func remove(_ item: BridgeItem) {
		items.removeValue(forKey: item.nodeId)
	}
}
