import Foundation
import Testing
@testable import FSKitBridge

struct MountPointNameTests {
	@Test(arguments: [
		("Tresor", "Tresor"),
		("Paren (1) a-b_c.d", "Paren (1) a-b_c.d"),
		("Tresor M\u{00FC}ller", "Tresor Muller"),
		// decomposed
		("Cafe\u{0301}", "Cafe"),
		("Stra\u{00DF}e", "Strasse"),
		("\u{4FDD}\u{9669}\u{7BB1}", "bao xian xiang"),
		("\u{1F512} Vault", "_ Vault")
	])
	func transliteratesToASCIIAndReplacesTheRest(volumeName: String, mountPointName: String) {
		#expect(MountPointName.of(volumeName) == mountPointName)
	}

	/// Characters that transliterate to a path separator or to a name of the directory itself or its parent.
	@Test(arguments: [
		("Vault\u{FF0F}", "Vault_"),
		("Q1\u{2044}Q2", "Q1_Q2"),
		("Notes \u{00BD}", "Notes  1_2"),
		("\u{2025}", "_"),
		("\u{FF0E}\u{FF0E}", "_"),
		("\u{FF0E}", "_")
	])
	func yieldsOneNameThatIsNeitherTheDirectoryNorItsParent(volumeName: String, mountPointName: String) {
		#expect(MountPointName.of(volumeName) == mountPointName)
	}

	@Test func leavesRoomForTheNumberOfATakenName() {
		// transliterates to 399 characters
		let name = MountPointName.of(String(repeating: "\u{4FDD}", count: 100))

		#expect(name.utf8.count == MountPointName.maxLength)
		#expect(name.utf8.count + " 9999".utf8.count <= Int(NAME_MAX))
	}
}
