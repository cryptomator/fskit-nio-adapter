import Testing
@testable import FSKitBridge

struct CacheValidityTests {
	private final class Item {}

	private static let file: UInt64 = 64
	private static let anyField = Messages.attributeSize | Messages.attributeMode | Messages.attributeModified

	private static func record(size: UInt64 = 3, mode: UInt16 = 0o644, modified: Int64 = 100, accessed: Int64 = 100, type: NodeType = .file, generation: UInt64) -> Attributes {
		Attributes(type: type, mode: mode, size: size, nodeId: file, parentId: Messages.rootNodeId, modified: Timestamp(seconds: modified, nanos: 0), accessed: Timestamp(seconds: accessed, nanos: 0), created: Timestamp(seconds: 1, nanos: 0), generation: generation)
	}

	/// A validity that tracks `item`, handed out with a file of 3 bytes at generation 10.
	private static func tracking(_ item: Item) -> CacheValidity<Item> {
		var validity = CacheValidity<Item>()
		validity.handedOut(item, record(generation: 10))
		return validity
	}

	private static func due(_ validity: inout CacheValidity<Item>) -> Bool {
		!validity.claimDue().isEmpty
	}

	private static func claimFirst(_ validity: inout CacheValidity<Item>) -> Item? {
		validity.claimDue().first
	}

	private static func admit(_ validity: inout CacheValidity<Item>, _ item: Item) -> CacheValidity<Item>.Admission {
		validity.admit(item, nodeId: file)
	}

	@Test func aRecordThatMatchesTheBaselineMakesNoRevokeDue() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.compare(Self.record(generation: 11), .locked)

		#expect(!Self.due(&validity))
	}

	@Test(arguments: [
		record(size: 11, generation: 11),
		record(mode: 0o600, generation: 11),
		record(modified: 200, generation: 11),
		record(type: .symlink, generation: 11)
	])
	func aDifferenceInSizeModeTimeOrTypeMakesARevokeDue(record: Attributes) {
		let item = Item()
		var validity = Self.tracking(item)

		validity.compare(record, .locked)

		#expect(Self.claimFirst(&validity) === item)
	}

	@Test func anAccessTimeAloneMakesNoRevokeDue() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.compare(Self.record(accessed: 500, generation: 11), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aRecordOlderThanTheBaselineIsIgnored() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.compare(Self.record(size: 11, generation: 9), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aReplyThatShowsTheChangeDoesNotMoveTheBaseline() {
		let item = Item()
		var validity = Self.tracking(item)

		// a GETATTR, a READ or another LOOKUP after the change: FSKit's cached pages are still those of 3 bytes
		validity.handedOut(item, Self.record(size: 11, generation: 11))
		validity.compare(Self.record(size: 11, generation: 12), .locked)

		#expect(Self.claimFirst(&validity) === item)
	}

	@Test(arguments: [true, false])
	func aDifferingRecordKeepsTheRevokeDueWhicheverOrderAMatchingOneArrivesIn(differingFirst: Bool) {
		let item = Item()
		var validity = Self.tracking(item)
		let differing = Self.record(size: 11, generation: differingFirst ? 11 : 12)
		let matching = Self.record(generation: differingFirst ? 12 : 11)

		for record in differingFirst ? [differing, matching] : [matching, differing] {
			validity.compare(record, .locked)
		}

		#expect(Self.claimFirst(&validity) === item)
	}

	@Test func aRecordOfAnUntrackedNodeIsIgnored() {
		var validity = CacheValidity<Item>()
		validity.handedOut(Item(), Self.record(type: .directory, generation: 10))

		validity.compare(Self.record(mode: 0o700, type: .directory, generation: 11), .locked)
		validity.compare(Self.record(size: 11, generation: 12), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aModeTheVolumeSetsMovesTheBaselineAndItsOtherFieldsAreCompared() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(mode: 0o600, generation: 11)))
		#expect(!Self.due(&validity))

		// a chmod after a change outside: its reply carries the new size and time as well
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(size: 11, mode: 0o640, modified: 200, generation: 12)))
		#expect(Self.claimFirst(&validity) === item)
	}

	@Test func aSizeAndTimeTheVolumeSetsMoveTheBaseline() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeSize | Messages.attributeModified, Self.record(size: 0, modified: 300, generation: 11)))
		validity.compare(Self.record(size: 0, modified: 300, generation: 12), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aSizeTheVolumeSetsAloneMovesTheTimeAsWell() {
		let item = Item()
		var validity = Self.tracking(item)

		// a truncate sends the size alone, and changing it moves the modification time
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeSize, Self.record(size: 0, modified: 300, generation: 11)))
		validity.compare(Self.record(size: 0, modified: 300, generation: 12), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aModeAndAWriteWhoseRepliesArriveInReverseOrderAreBothApplied() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .write(end: 9, Self.record(size: 9, mode: 0o600, modified: 200, generation: 12)))
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(mode: 0o600, generation: 11)))
		validity.compare(Self.record(size: 9, mode: 0o600, modified: 200, generation: 13), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aGrowingWriteWhoseReplyArrivesAfterThatOfALaterWriteWithinItIsApplied() {
		let item = Item()
		var validity = Self.tracking(item)

		// the server applied the write to 20 bytes first, then one within them
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .write(end: 9, Self.record(size: 20, modified: 300, generation: 12)))
		validity.ownChangeEnded(Self.file, .write(end: 20, Self.record(size: 20, modified: 200, generation: 11)))
		validity.compare(Self.record(size: 20, modified: 300, generation: 13), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aWriteThatGrowsTheFileMovesItsSizeAndTime() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .write(end: 9, Self.record(size: 9, modified: 200, generation: 11)))
		validity.compare(Self.record(size: 9, modified: 200, generation: 12), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aWriteWithinTheFileWhoseReplyShowsAnotherSizeMakesARevokeDue() {
		let item = Item()
		var validity = Self.tracking(item)

		// the file grew to 11 bytes outside, and the volume wrote its first byte
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .write(end: 1, Self.record(size: 11, modified: 200, generation: 11)))

		#expect(Self.claimFirst(&validity) === item)
	}

	@Test func aRecordSampledWhileTheVolumesOwnChangeIsInFlightAndNoNewerThanItIsDropped() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.ownChangeBegan(Self.file, setting: Messages.attributeSize)
		// a READDIR page, sampled while the server extends the file in chunks
		validity.compare(Self.record(size: 5, modified: 150, generation: 11), .sampled)
		#expect(!Self.due(&validity))
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeSize, Self.record(size: 9, modified: 200, generation: 12)))

		#expect(!Self.due(&validity))
	}

	@Test(arguments: [false, true])
	func aRecordSampledAfterTheVolumesOwnChangeIsJudgedOnceItWasApplied(changedOutside: Bool) {
		let item = Item()
		var validity = Self.tracking(item)

		validity.ownChangeBegan(Self.file, setting: Messages.attributeSize)
		// sampled after the server's reply to the change, and arrived before it
		validity.compare(changedOutside ? Self.record(size: 5, modified: 150, generation: 13) : Self.record(size: 9, modified: 200, generation: 13), .sampled)
		#expect(!Self.due(&validity))
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeSize, Self.record(size: 9, modified: 200, generation: 12)))

		#expect(Self.due(&validity) == changedOutside)
	}

	@Test func aRecordThatALookupHandsOutWhileTheVolumesOwnWriteIsInFlightIsJudgedAsSampled() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeSize | Messages.attributeModified)

		validity.handedOut(item, Self.record(size: 5, modified: 150, generation: 11))
		validity.ownChangeEnded(Self.file, .write(end: 9, Self.record(size: 9, modified: 200, generation: 12)))

		#expect(!Self.due(&validity))
	}

	@Test func theReplyOfAnOwnChangeThatEndsWhileAnotherIsInFlightIsJudgedAtOnceInTheFieldsNoneSets() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeMode)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeMode)

		// shrunk outside before the first mode was set
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(size: 2, mode: 0o600, generation: 11)))

		#expect(Self.admit(&validity, item) == .revoke)
	}

	@Test func theFieldsAnEarlierBatchCouldSetAreJudgedAtOnceInALaterOne() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeSize | Messages.attributeModified)
		validity.ownChangeEnded(Self.file, .write(end: 9, Self.record(size: 9, modified: 200, generation: 11)))
		validity.ownChangeBegan(Self.file, setting: Messages.attributeMode)

		validity.compare(Self.record(size: 11, modified: 300, generation: 12), .locked)

		#expect(Self.admit(&validity, item) == .revoke)
	}

	@Test func aRecordSampledWhileNoOwnChangeIsInFlightIsJudged() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.compare(Self.record(size: 5, modified: 150, generation: 11), .sampled)

		#expect(Self.due(&validity))
	}

	@Test func aRecordThatWaitedForAFailedChangeIsJudged() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.compare(Self.record(size: 11, generation: 11), .locked)
		validity.ownChangeEnded(Self.file, nil)

		#expect(Self.claimFirst(&validity) === item)
	}

	@Test func anOlderReplyOfAnOverlappingChangeDoesNotMoveTheBaselineBack() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeEnded(Self.file, .write(end: 20, Self.record(size: 20, modified: 300, generation: 12)))
		validity.ownChangeEnded(Self.file, .write(end: 9, Self.record(size: 9, modified: 200, generation: 11)))
		validity.compare(Self.record(size: 20, modified: 300, generation: 13), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func aMissingFileMakesARevokeDue() {
		let item = Item()
		var validity = Self.tracking(item)

		validity.missing(Self.file)

		#expect(Self.claimFirst(&validity) === item)
	}

	@Test func aRevokeIsClaimedOnceAndAnOpenWhileItRunsIsRefused() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.compare(Self.record(size: 11, generation: 11), .locked)

		#expect(Self.claimFirst(&validity) === item)
		#expect(!Self.due(&validity))
		#expect(Self.admit(&validity, item) == .refuse)
	}

	@Test func anOpenTakesTheRevokeOfItsOwnItemOnlyAndOnlyOnce() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.compare(Self.record(size: 11, generation: 11), .locked)

		#expect(Self.admit(&validity, Item()) == .grant)
		#expect(Self.admit(&validity, item) == .revoke)
		#expect(Self.admit(&validity, item) == .refuse)
		#expect(!Self.due(&validity))
	}

	@Test func anOpenOfACurrentItemIsGranted() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.compare(Self.record(generation: 11), .locked)

		#expect(Self.admit(&validity, item) == .grant)
	}

	@Test func aRevokedItemIsNeitherJudgedNorClaimedAgainUntilItIsReclaimed() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.compare(Self.record(size: 11, generation: 11), .locked)
		_ = validity.claimDue()

		validity.revokeEnded(item, nodeId: Self.file, success: true)
		validity.compare(Self.record(size: 20, generation: 12), .locked)
		validity.missing(Self.file)

		#expect(!Self.due(&validity))
		#expect(Self.admit(&validity, item) == .refuse)
		validity.reclaimed(Self.file)
		#expect(Self.admit(&validity, item) == .grant)
	}

	@Test func aFailedRevokeIsDueAgain() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.compare(Self.record(size: 11, generation: 11), .locked)
		_ = validity.claimDue()

		validity.revokeEnded(item, nodeId: Self.file, success: false)

		#expect(Self.claimFirst(&validity) === item)
	}

	@Test func theEndOfARevokeOfAnotherItemOfTheNodeChangesNothing() {
		let revoked = Item()
		var validity = Self.tracking(revoked)
		validity.compare(Self.record(size: 11, generation: 11), .locked)
		_ = validity.claimDue()
		validity.reclaimed(Self.file)
		let replacement = Item()
		validity.handedOut(replacement, Self.record(size: 11, generation: 12))

		validity.revokeEnded(revoked, nodeId: Self.file, success: false)

		#expect(!Self.due(&validity))
		#expect(Self.admit(&validity, replacement) == .grant)
	}

	@Test(arguments: [true, false])
	func aRevokedItemThatALookupHandsOutAgainStaysRevoked(lookupFirst: Bool) {
		let item = Item()
		var validity = Self.tracking(item)
		validity.compare(Self.record(size: 11, generation: 11), .locked)
		_ = validity.claimDue()

		// a process holds the item open, so FSKit hands out the item with the pages it cached
		if lookupFirst {
			validity.handedOut(item, Self.record(size: 11, generation: 12))
			validity.revokeEnded(item, nodeId: Self.file, success: true)
		} else {
			validity.revokeEnded(item, nodeId: Self.file, success: true)
			validity.handedOut(item, Self.record(size: 11, generation: 12))
		}

		#expect(Self.admit(&validity, item) == .refuse)
		#expect(!Self.due(&validity))
	}

	@Test func aRevokeThatFailsAfterALookupHandedTheItemOutAgainIsDueAgain() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.compare(Self.record(size: 11, generation: 11), .locked)
		_ = validity.claimDue()
		validity.handedOut(item, Self.record(size: 11, generation: 12))
		#expect(Self.admit(&validity, item) == .refuse)

		validity.revokeEnded(item, nodeId: Self.file, success: false)

		#expect(Self.admit(&validity, item) == .revoke)
	}

	@Test func anOwnChangeInFlightWhileALookupHandsOutAnItemBeingRevokedIsNotTakenForAnOutsideChange() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.compare(Self.record(size: 11, generation: 11), .locked)
		_ = validity.claimDue()
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.handedOut(item, Self.record(size: 11, generation: 12))
		validity.revokeEnded(item, nodeId: Self.file, success: true)

		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(size: 11, mode: 0o755, generation: 13)))
		validity.compare(Self.record(size: 11, mode: 0o755, generation: 14), .locked)

		#expect(!Self.due(&validity))
	}

	@Test func anOutsideChangeInTheReplyOfAnOwnChangeIsNotHiddenByALaterOneThatSetsTheTime() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Self.anyField)
		validity.ownChangeBegan(Self.file, setting: Self.anyField)

		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeModified, Self.record(mode: 0o755, modified: 500, generation: 12)))
		// edited in place outside before the mode was set: same size, another time
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(mode: 0o755, modified: 300, generation: 11)))

		#expect(Self.due(&validity))
	}

	@Test func aRecordReadUnderTheDataLockIsJudgedAgainstTheBaselineOfItsGenerationOnly() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeMode)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeModified)

		// edited in place outside before the mode was set; the time set later happens to be the same
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(mode: 0o755, modified: 300, generation: 11)))
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeModified, Self.record(mode: 0o755, modified: 300, generation: 12)))

		#expect(Self.due(&validity))
	}

	@Test func aRecordReadUnderTheDataLockDoesNotMatchAFieldTheVolumeChangedBeforeIt() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeMode)

		// set back outside after the volume's mode took effect
		validity.compare(Self.record(mode: 0o644, generation: 12), .locked)
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(mode: 0o755, generation: 11)))

		#expect(Self.due(&validity))
	}

	@Test func anOpenThatFindsAnOutsideChangeWhileAnOwnChangeIsInFlightIsRefused() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeMode)

		validity.compare(Self.record(size: 11, mode: 0o755, generation: 12), .locked)

		#expect(Self.admit(&validity, item) == .revoke)
	}

	@Test func anOpenWhoseRecordDiffersOnlyInAFieldAnOwnChangeInFlightSetsIsGranted() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeMode)

		validity.compare(Self.record(mode: 0o755, generation: 12), .locked)
		#expect(Self.admit(&validity, item) == .grant)
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeMode, Self.record(mode: 0o755, generation: 11)))

		#expect(!Self.due(&validity))
	}

	@Test func aTimeThatTheVolumesOwnSizeChangeMovesIsNotTakenForAnOutsideChange() {
		let item = Item()
		var validity = Self.tracking(item)
		validity.ownChangeBegan(Self.file, setting: Messages.attributeSize)

		validity.compare(Self.record(size: 9, modified: 200, generation: 12), .locked)
		validity.ownChangeEnded(Self.file, .setattr(applied: Messages.attributeSize, Self.record(size: 9, modified: 200, generation: 11)))

		#expect(!Self.due(&validity))
	}
}
