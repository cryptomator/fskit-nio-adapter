import Testing
@testable import FSKitBridge

struct ItemTableTests {
	private final class Item {
		let nodeId: UInt64

		init(nodeId: UInt64) {
			self.nodeId = nodeId
		}
	}

	@Test func oneInstancePerNodeIdWithItsLookupsCounted() {
		var table = ItemTable(make: Item.init(nodeId:))
		let first = table.item(for: 64)
		let second = table.item(for: 64)
		let other = table.item(for: 65)

		#expect(first === second)
		#expect(first.nodeId == 64)
		#expect(other.nodeId == 65)
		#expect(table.removeIfCurrent(first, nodeId: 64) == 2)
		#expect(table.removeIfCurrent(other, nodeId: 65) == 1)
	}

	@Test func anInstanceThatALaterLookupReplacedIsNotRemoved() {
		var table = ItemTable(make: Item.init(nodeId:))
		let reclaimed = table.item(for: 64)
		#expect(table.removeIfCurrent(reclaimed, nodeId: 64) == 1)
		let replacement = table.item(for: 64)

		#expect(table.removeIfCurrent(reclaimed, nodeId: 64) == nil)
		#expect(table.removeIfCurrent(replacement, nodeId: 64) == 1)
	}
}
