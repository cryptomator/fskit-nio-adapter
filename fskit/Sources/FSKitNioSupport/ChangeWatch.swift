import CoreServices
import Foundation

/// Reports that something in a directory tree changed, through an FSEvents stream that leaves out the changes of this process.
final class ChangeWatch {
	/// How long the stream gathers events before it reports them.
	static let latency: CFTimeInterval = 0.3

	private let queue = DispatchQueue(label: "org.cryptomator.fskit.ChangeWatch")
	private let callback: @convention(c) () -> Void
	private var stream: FSEventStreamRef?

	/// Starts watching.
	///
	/// - Parameter callback: Runs on a queue of the watch whenever events arrive. It learns nothing about them.
	/// - Returns: `nil` if the stream could not be started.
	init?(path: String, callback: @convention(c) () -> Void) {
		self.callback = callback
		var context = FSEventStreamContext(version: 0, info: Unmanaged.passUnretained(self).toOpaque(), retain: nil, release: nil, copyDescription: nil)
		let report: FSEventStreamCallback = { _, info, _, _, _, _ in
			guard let info else {
				return
			}
			Unmanaged<ChangeWatch>.fromOpaque(info).takeUnretainedValue().callback()
		}
		let flags = FSEventStreamCreateFlags(kFSEventStreamCreateFlagIgnoreSelf)
		guard let stream = FSEventStreamCreate(kCFAllocatorDefault, report, &context, [path] as CFArray, FSEventStreamEventId(kFSEventStreamEventIdSinceNow), Self.latency, flags) else {
			return nil
		}
		FSEventStreamSetDispatchQueue(stream, queue)
		guard FSEventStreamStart(stream) else {
			FSEventStreamInvalidate(stream)
			FSEventStreamRelease(stream)
			return nil
		}
		self.stream = stream
	}

	/// Stops watching. Once this returns, the callback no longer runs.
	func stop() {
		// on the queue, so that a callback in progress has returned
		queue.sync {
			guard let stream else {
				return
			}
			FSEventStreamStop(stream)
			FSEventStreamInvalidate(stream)
			FSEventStreamRelease(stream)
			self.stream = nil
		}
	}
}
