import FSKit

/// Stands for the node with the given id on the server, which holds all other state of the item.
final class BridgeItem: FSItem {

	let nodeId: UInt64

	init(nodeId: UInt64) {
		self.nodeId = nodeId
		super.init()
	}
}
