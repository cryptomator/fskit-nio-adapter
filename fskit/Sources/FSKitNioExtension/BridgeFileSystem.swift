import FSKit
import FSKitBridge
import Foundation
import os

/// Presents the session described by the manifest in the directory passed to `mount` as a volume.
final class BridgeFileSystem: FSUnaryFileSystem, FSUnaryFileSystemOperations {

	private let logger = Logger(subsystem: "org.cryptomator.fskit", category: "BridgeFileSystem")
	private let volumes = OSAllocatedUnfairLock<[UUID: BridgeVolume]>(uncheckedState: [:])

	func probeResource(
		resource: FSResource,
		replyHandler: @escaping (FSProbeResult?, (any Error)?) -> Void
	) {
		guard let (_, uuid) = rendezvous(resource) else {
			replyHandler(FSProbeResult.notRecognized, nil)
			return
		}
		replyHandler(FSProbeResult.usable(name: "", containerID: FSContainerIdentifier(uuid: uuid)), nil)
	}

	func loadResource(
		resource: FSResource,
		options: FSTaskOptions,
		replyHandler: @escaping (FSVolume?, (any Error)?) -> Void
	) {
		guard let (url, uuid) = rendezvous(resource) else {
			logger.error("loadResource: not a rendezvous directory")
			replyHandler(nil, fs_errorForPOSIXError(POSIXError.EINVAL.rawValue))
			return
		}
		guard url.startAccessingSecurityScopedResource() else {
			logger.error("loadResource: cannot access security-scoped resource")
			replyHandler(nil, fs_errorForPOSIXError(POSIXError.EACCES.rawValue))
			return
		}
		do {
			let manifest = try Manifest.read(from: url)
			let volume = BridgeVolume(uuid: uuid, name: manifest.volumeName, client: try BridgeClient(manifest: manifest))
			volumes.withLockUnchecked { $0[uuid] = volume }
			containerStatus = .ready
			replyHandler(volume, nil)
		} catch {
			url.stopAccessingSecurityScopedResource()
			logger.error("loadResource: connecting to the session failed: \(error, privacy: .public)")
			replyHandler(nil, fs_errorForPOSIXError(POSIXError.EIO.rawValue))
		}
	}

	func unloadResource(
		resource: FSResource,
		options: FSTaskOptions,
		replyHandler reply: @escaping ((any Error)?) -> Void
	) {
		if let (url, uuid) = rendezvous(resource) {
			volumes.withLockUnchecked { $0.removeValue(forKey: uuid) }?.disconnect()
			url.stopAccessingSecurityScopedResource()
		}
		reply(nil)
	}

	/// The directory that was passed to `mount`, if it is one a session created: its name is the UUID that identifies the mount.
	private func rendezvous(_ resource: FSResource) -> (URL, UUID)? {
		guard let url = (resource as? FSPathURLResource)?.url, let uuid = UUID(uuidString: url.lastPathComponent) else {
			return nil
		}
		return (url, uuid)
	}
}
