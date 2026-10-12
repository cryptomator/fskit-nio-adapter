import Foundation
import os
import Testing
@testable import FSKitNioSupport

struct LateMountTests {
	private static let rendezvousName = "0F4E1C9A-6B7D-4A52-9E3B-2D8C5F7A1B60"
	private static let path = "/Volumes/Tresor 1"

	/// Cleans the volume FSKit reports at `path` with a mount table that names `source` there, and returns the outcome with the paths that were unmounted.
	private func clean(source: String?, unmountSucceeds: Bool = true) -> (LateMount.Outcome, [String]) {
		let unmounted = OSAllocatedUnfairLock<[String]>(initialState: [])
		let lateMount = LateMount(rendezvousName: Self.rendezvousName, source: { $0 == Self.path ? source : nil }, unmount: { path in
			unmounted.withLock { $0.append(path) }
			return unmountSucceeds
		})
		// FSKit reports a directory URL, whose path ends in a slash
		let outcome = lateMount.clean(mountPath: URL(filePath: Self.path, directoryHint: .isDirectory))
		return (outcome, unmounted.withLock { $0 })
	}

	@Test func unmountsAVolumeMountedFromTheAttempt() {
		let (outcome, unmounted) = clean(source: "file:///private/var/folders/xy/T/\(Self.rendezvousName)/")

		#expect(outcome == .unmounted)
		#expect(unmounted == [Self.path])
	}

	@Test func reportsAFailingUnmount() {
		let (outcome, unmounted) = clean(source: "file:///private/var/folders/xy/T/\(Self.rendezvousName)/", unmountSucceeds: false)

		#expect(outcome == .unmountFailed)
		#expect(unmounted == [Self.path])
	}

	@Test func leavesAVolumeOfAnotherAttemptAlone() {
		let (outcome, unmounted) = clean(source: "file:///private/var/folders/xy/T/5B1D7E2F-3C4A-4F6B-8D9E-0A1B2C3D4E5F/")

		#expect(outcome == .notOurs)
		#expect(unmounted.isEmpty)
	}

	@Test func leavesThePathAloneWhenItsSourceCannotBeRead() {
		let (outcome, unmounted) = clean(source: nil)

		#expect(outcome == .unknown)
		#expect(unmounted.isEmpty)
	}
}

struct MountTableTests {
	@Test func readsTheSourceOfAMountPoint() {
		#expect(LateMount.sourceOfLastMount(at: "/dev") == "devfs")
	}

	@Test func findsNothingAtAPathWhereNothingIsMounted() {
		#expect(LateMount.sourceOfLastMount(at: "/usr") == nil)
	}
}
