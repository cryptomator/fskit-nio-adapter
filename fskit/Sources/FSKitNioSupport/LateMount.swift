import Foundation

/// Unmounts a volume that FSKit reports mounted after the caller stopped waiting for it. No API cancels a pending mount, and closing the session does not stop one whose volume is already activated.
public struct LateMount: Sendable {
	private typealias MountInfo = statfs

	public enum Outcome: Equatable, Sendable {
		case unmounted
		case unmountFailed
		/// Something mounted from elsewhere is at the path, so it was left alone.
		case notOurs
		/// Nothing is mounted at the path, or the mount table could not be read, so nothing was unmounted.
		case unknown
	}

	private let rendezvousName: String
	private let source: @Sendable (String) -> String?
	private let unmount: @Sendable (String) -> Bool

	/// - Parameters:
	///   - rendezvousName: The name of the directory the mount was asked for, a UUID that identifies the attempt.
	///   - source: Tells what is mounted at a path, as the mount table names its source, or `nil` if nothing is mounted there or the table cannot be read.
	///   - unmount: Unmounts a path and tells whether that succeeded.
	public init(rendezvousName: String, source: @escaping @Sendable (String) -> String?, unmount: @escaping @Sendable (String) -> Bool) {
		self.rendezvousName = rendezvousName
		self.source = source
		self.unmount = unmount
	}

	/// Reads the mount table without touching any mounted file system. Unmounts with `diskutil unmount force`, since `umount` is refused for a volume FSKit mounted in `/Volumes`.
	public init(rendezvousName: String) {
		self.init(rendezvousName: rendezvousName, source: { LateMount.sourceOfLastMount(at: $0) }, unmount: { LateMount.forceUnmount($0) })
	}

	/// Unmounts the volume at `mountPath` only if the mount table attributes it to the attempt.
	public func clean(mountPath: URL) -> Outcome {
		// the path of a directory URL ends in a slash, which the mount table leaves out
		var path = mountPath.path(percentEncoded: false)
		if path.count > 1, path.hasSuffix("/") {
			path.removeLast()
		}
		guard let source = source(path) else {
			return .unknown
		}
		guard source.contains(rendezvousName) else {
			return .notOurs
		}
		return unmount(path) ? .unmounted : .unmountFailed
	}

	static func sourceOfLastMount(at path: String) -> String? {
		let count = getfsstat(nil, 0, MNT_NOWAIT)
		guard count > 0 else {
			return nil
		}
		// room for one more than counted, so that a buffer that is not full holds every mount
		var capacity = Int(count) + 1
		while true {
			var mounts = [MountInfo](repeating: MountInfo(), count: capacity)
			let filled = Int(mounts.withUnsafeMutableBufferPointer { getfsstat($0.baseAddress, Int32($0.count * MemoryLayout<MountInfo>.stride), MNT_NOWAIT) })
			guard filled >= 0 else {
				return nil
			}
			guard filled == capacity else {
				return mounts.prefix(filled).last { string(of: $0.f_mntonname) == path }.flatMap { string(of: $0.f_mntfromname) }
			}
			// a full buffer may have cut off the newest mounts, which getfsstat lists last
			capacity *= 2
		}
	}

	private static func string(of characters: some Any) -> String? {
		withUnsafeBytes(of: characters) { String(bytes: $0.prefix { $0 != 0 }, encoding: .utf8) }
	}

	private static func forceUnmount(_ path: String) -> Bool {
		let diskutil = Process()
		diskutil.executableURL = URL(filePath: "/usr/sbin/diskutil")
		diskutil.arguments = ["unmount", "force", path]
		diskutil.standardOutput = FileHandle.nullDevice
		diskutil.standardError = FileHandle.nullDevice
		do {
			try diskutil.run()
		} catch {
			return false
		}
		diskutil.waitUntilExit()
		return diskutil.terminationStatus == 0
	}
}
