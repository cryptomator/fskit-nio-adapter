import FSKit

/// Stands for the node with the given id on the server, which holds all other state of the item.
final class BridgeItem: FSItem {
	let nodeId: UInt64
	/// Whether the item is a file created through the volume whose channel from the create is still open. The server opened that channel for reading and writing, so an open needs no request. Confined to the volume's queue.
	var openedByCreate = false

	init(nodeId: UInt64) {
		self.nodeId = nodeId
		super.init()
	}
}
