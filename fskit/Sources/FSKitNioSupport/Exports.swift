import Foundation
import FSKit
import os
import Security

/// Checks the app this process runs from for the extension of a file system type, without asking FSKit.
///
/// - Returns: The code of `notInApp`, `notEmbedded` or `embedded`.
@_cdecl("fskitnio_embedded_extension")
public func embeddedExtension(shortName: UnsafePointer<CChar>) -> Int32 {
	ExtensionStatus(Embedding(of: String(cString: shortName), in: Bundle.main.bundleURL)).rawValue
}

/// Checks the app this process runs from for the extension of a file system type, and asks FSKit whether it is enabled. FSKit lists an extension only to a process signed by the extension's team.
///
/// - Returns: The code of any status but `embedded`.
@_cdecl("fskitnio_extension_status")
public func extensionStatus(shortName: UnsafePointer<CChar>, timeoutMillis: Int32) -> Int32 {
	let embedding = Embedding(of: String(cString: shortName), in: Bundle.main.bundleURL)
	guard case let .embedded(bundleIdentifier) = embedding else {
		return ExtensionStatus(embedding).rawValue
	}
	let outcome = FetchOutcome<[InstalledExtension]>(waitingAtMost: .milliseconds(Int(timeoutMillis))) { completion in
		FSClient.shared.fetchInstalledExtensions { modules, error in
			completion(modules?.map { InstalledExtension(bundleIdentifier: $0.bundleIdentifier, isEnabled: $0.isEnabled) }, error)
		}
	}
	switch outcome {
	case let .fetched(installed):
		return ExtensionStatus(bundleIdentifier: bundleIdentifier, installed: installed).rawValue
	case .failed:
		return ExtensionStatus.fskitFailed.rawValue
	case .timedOut:
		return ExtensionStatus.timedOut.rawValue
	}
}

/// Opens System Settings at File System Extensions.
///
/// - Returns: 1 if it opened, 0 if not.
@_cdecl("fskitnio_open_extension_settings")
public func openExtensionSettings() -> Int32 {
	FSClient.shared.openFileSystemExtensionsSettings() ? 1 : 0
}

/// Mounts the session described by the manifest in a directory through FSKit, at a path the system chooses in `/Volumes`. FSKit mounts this way only for a process with the entitlement `com.apple.developer.fskit.mount`.
///
/// A volume FSKit reports after the timeout is unmounted then if the mount table attributes it to this attempt, as long as this process runs.
///
/// - Parameters:
///   - rendezvousDir: The directory that holds the manifest, named with the UUID that identifies the mount.
///   - readOnly: Nonzero to mount read-only.
///   - buffer: Receives, as a NUL-terminated string cut to `bufferSize`, the mount path for `mounted` and a description of the error for `failed`.
/// - Returns: The code of a `MountStatus`.
@_cdecl("fskitnio_mount")
// swiftlint:disable:next function_parameter_count
public func mount(shortName: UnsafePointer<CChar>, rendezvousDir: UnsafePointer<CChar>, readOnly: Int32, timeoutMillis: Int32, buffer: UnsafeMutablePointer<CChar>, bufferSize: Int32) -> Int32 {
	let fsType = String(cString: shortName)
	guard case let .embedded(bundleIdentifier) = Embedding(of: fsType, in: Bundle.main.bundleURL) else {
		write("This process runs from no app that embeds the FSKit extension for \(fsType)", to: buffer, size: bufferSize)
		return MountStatus.failed.rawValue
	}
	guard holdsMountEntitlement() else {
		return MountStatus.notEntitled.rawValue
	}
	let url = URL(filePath: String(cString: rendezvousDir), directoryHint: .isDirectory)
	let resource: FSPathURLResource
	do {
		// the extension accepts only a URL it can access as a security-scoped resource
		var isStale = false
		let bookmark = try url.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil)
		resource = try FSPathURLResource(url: URL(resolvingBookmarkData: bookmark, options: .withSecurityScope, relativeTo: nil, bookmarkDataIsStale: &isStale), writable: readOnly == 0)
	} catch {
		write("Failed to create a security-scoped bookmark of \(url.path(percentEncoded: false)): \(error.localizedDescription)", to: buffer, size: bufferSize)
		return MountStatus.failed.rawValue
	}
	let lateMount = LateMount(rendezvousName: url.lastPathComponent)
	let outcome = FetchOutcome<URL>(waitingAtMost: .milliseconds(Int(timeoutMillis)), lateValue: { mountPath in
		let cleaned = lateMount.clean(mountPath: mountPath)
		lateMountLogger.notice("FSKit mounted \(mountPath.path(percentEncoded: false), privacy: .public) after the timeout: \(String(describing: cleaned), privacy: .public)")
	}, for: { completion in
		FSClient.shared.mountSingleVolume(resource: resource, bundleID: bundleIdentifier, options: readOnly == 0 ? [] : ["-o", "rdonly"], completionHandler: completion)
	})
	switch outcome {
	case let .fetched(mountPath):
		write(mountPath.path(percentEncoded: false), to: buffer, size: bufferSize)
		return MountStatus.mounted.rawValue
	case let .failed(error):
		let error = error as NSError
		write("\(error.domain) \(error.code): \(error.localizedDescription)", to: buffer, size: bufferSize)
		return MountStatus.failed.rawValue
	case .timedOut:
		return MountStatus.timedOut.rawValue
	}
}

private let lateMountLogger = Logger(subsystem: "org.cryptomator.fskit", category: "LateMount")

private func holdsMountEntitlement() -> Bool {
	guard let task = SecTaskCreateFromSelf(nil) else {
		return false
	}
	return SecTaskCopyValueForEntitlement(task, "com.apple.developer.fskit.mount" as CFString, nil) as? Bool == true
}

private func write(_ text: String, to buffer: UnsafeMutablePointer<CChar>, size: Int32) {
	guard size > 0 else {
		return
	}
	strlcpy(buffer, text, Int(size))
}
