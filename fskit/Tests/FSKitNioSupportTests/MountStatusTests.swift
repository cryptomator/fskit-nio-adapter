import Testing
@testable import FSKitNioSupport

struct MountStatusTests {
	/// The codes the Java test of `NativeVolumeMounter` decodes.
	@Test(arguments: [
		(MountStatus.mounted, Int32(0)),
		(.notEntitled, 1),
		(.failed, -1),
		(.timedOut, -2)
	])
	func eachStatusHasTheCodeTheJavaProviderDecodes(status: MountStatus, code: Int32) {
		#expect(status.rawValue == code)
	}
}
