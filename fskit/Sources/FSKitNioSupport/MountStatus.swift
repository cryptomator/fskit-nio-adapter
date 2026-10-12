/// The answers of the export that mounts through FSKit. `NativeVolumeMounter` in the Java provider mirrors the codes.
public enum MountStatus: Int32, Sendable {
	/// The buffer holds the path FSKit mounted the volume at.
	case mounted = 0
	/// The process lacks `com.apple.developer.fskit.mount`, so FSKit was not asked.
	case notEntitled = 1
	/// The buffer holds a description of the error.
	case failed = -1
	case timedOut = -2
}
