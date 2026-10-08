import Testing
@testable import FSKitBridge

struct AttributeLedgerTests {
	private static func attributes(size: UInt64, generation: UInt64) -> Attributes {
		let time = Timestamp(seconds: 1_700_000_000, nanos: 0)
		return Attributes(type: .file, mode: 0o644, size: size, nodeId: 64, parentId: Messages.rootNodeId, modified: time, accessed: time, created: time, generation: generation)
	}

	/// The handler that began first may also have had its response first: it is the order of populating that counts.
	@Test func anOlderRecordPopulatedAfterANewerOneYieldsTheNewer() {
		var ledger = AttributeLedger()
		let older = ledger.begin()
		let newer = ledger.begin()

		#expect(ledger.newest(Self.attributes(size: 2, generation: 20)).size == 2)
		ledger.finish(newer)
		#expect(ledger.newest(Self.attributes(size: 1, generation: 10)).size == 2)
		ledger.finish(older)
	}

	@Test func aRecordIsDroppedOnceEveryHandlerThatBeganBeforeItIsDone() {
		var ledger = AttributeLedger()
		let earlier = ledger.begin()
		let keeping = ledger.begin()
		_ = ledger.newest(Self.attributes(size: 2, generation: 20))
		ledger.finish(keeping)
		ledger.finish(earlier)

		// only a handler that began after the record was dropped could carry this, which the server never sends
		let later = ledger.begin()
		#expect(ledger.newest(Self.attributes(size: 1, generation: 10)).size == 1)
		ledger.finish(later)
		#expect(ledger.isIdle)
	}

	@Test func aRecordIsKeptWhileAHandlerThatBeganBeforeItIsOutAlthoughOthersFinish() {
		var ledger = AttributeLedger()
		let first = ledger.begin()
		let slow = ledger.begin()
		let keeping = ledger.begin()
		_ = ledger.newest(Self.attributes(size: 2, generation: 20))
		ledger.finish(keeping)
		let later = ledger.begin()
		ledger.finish(first)

		#expect(ledger.newest(Self.attributes(size: 1, generation: 10)).size == 2)
		ledger.finish(later)
		ledger.finish(slow)
	}

	@Test func theFreeSpaceOfTheNewestSampleWinsAndAnUnknownOneChangesNothing() {
		var ledger = AttributeLedger()

		#expect(ledger.newest(FreeSpace(usableBytes: 200, generation: 20)) == 200)
		#expect(ledger.newest(FreeSpace(usableBytes: 100, generation: 10)) == nil)
		#expect(ledger.newest(FreeSpace(usableBytes: Messages.unknownUsableBytes, generation: 30)) == nil)
		#expect(ledger.newest(FreeSpace(usableBytes: 300, generation: 25)) == 300)
	}
}
