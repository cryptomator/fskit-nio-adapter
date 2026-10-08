import Foundation
import FSKit

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
