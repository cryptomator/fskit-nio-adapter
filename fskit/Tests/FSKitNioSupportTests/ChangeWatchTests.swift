import Foundation
import os
import Testing
@testable import FSKitNioSupport

/// The calls of the callback, which a C function pointer can reach only through a global.
private let reports = OSAllocatedUnfairLock(initialState: 0)

@Suite(.serialized)
struct ChangeWatchTests {
	private static func directory() throws -> URL {
		let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
		try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
		return directory.resolvingSymlinksInPath()
	}

	/// Writes a file from another process, since the watch leaves out this one's changes.
	private static func writeFromAnotherProcess(_ file: URL) throws {
		let process = try Process.run(URL(filePath: "/bin/sh"), arguments: ["-c", "echo changed > \"$1\"", "sh", file.path(percentEncoded: false)])
		process.waitUntilExit()
	}

	private static func reported(within seconds: Double) -> Bool {
		let deadline = Date.now.addingTimeInterval(seconds)
		while Date.now < deadline {
			if reports.withLock({ $0 }) > 0 {
				return true
			}
			Thread.sleep(forTimeInterval: 0.05)
		}
		return false
	}

	@Test func reportsAChangeAnotherProcessMakesAndNothingOnceStopped() throws {
		let directory = try Self.directory()
		defer { try? FileManager.default.removeItem(at: directory) }
		reports.withLock { $0 = 0 }
		let watch = try #require(ChangeWatch(path: directory.path(percentEncoded: false)) { reports.withLock { $0 += 1 } })

		try Self.writeFromAnotherProcess(directory.appendingPathComponent("file.txt"))
		#expect(Self.reported(within: 5))

		watch.stop()
		reports.withLock { $0 = 0 }
		try Self.writeFromAnotherProcess(directory.appendingPathComponent("other.txt"))
		#expect(!Self.reported(within: 2 * ChangeWatch.latency + 1))
	}

	@Test func leavesOutTheChangesOfThisProcess() throws {
		let directory = try Self.directory()
		defer { try? FileManager.default.removeItem(at: directory) }
		reports.withLock { $0 = 0 }
		let watch = try #require(ChangeWatch(path: directory.path(percentEncoded: false)) { reports.withLock { $0 += 1 } })
		defer { watch.stop() }

		try Data("own".utf8).write(to: directory.appendingPathComponent("file.txt"))

		#expect(!Self.reported(within: 2 * ChangeWatch.latency + 1))
	}
}
