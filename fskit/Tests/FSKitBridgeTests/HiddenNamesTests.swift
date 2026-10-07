import Testing
@testable import FSKitBridge

struct HiddenNamesTests {
	/// Uses the same table as the Java test of `HiddenNames`.
	@Test(arguments: [
		(".DS_Store", true),
		("._a", true),
		("._._", true),
		// a combining mark after the underscore, which Swift joins with it into one character
		("._\u{0301}", true),
		("._", false),
		(".ds_store", false),
		(".DS_Store.txt", false),
		("a._b", false),
		("_a", false),
		(".a", false),
		("", false)
	])
	func hidesDSStoreAndEveryNameLongerThanItsPrefixThatStartsWithIt(name: String, hidden: Bool) {
		#expect(HiddenNames.contains(name) == hidden)
	}
}
