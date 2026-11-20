import FSKit

/// Stands for the node with the given id on the server, which holds all other state of the item. Sendable, since the volume's state lock guards its mutable properties.
final class BridgeItem: FSItem, @unchecked Sendable {
	let nodeId: UInt64
	/// Whether the item is a file created through the volume whose channel from the create is still open. The server opened that channel for reading and writing, so an open needs no request. Guarded by the volume's state lock.
	var openedByCreate = false
	/// Whether FSKit holds the item open: an open granted it a cache, and no close followed. Guarded by the volume's state lock.
	var isOpen = false

	init(nodeId: UInt64) {
		self.nodeId = nodeId
		super.init()
	}
}
