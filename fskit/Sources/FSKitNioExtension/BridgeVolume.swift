import Foundation
import FSKit
import FSKitBridge
import os

/// Forwards the operations it supports to the server of the mount.
///
/// Each of their handlers dispatches one block to the volume's serial queue. The block makes the blocking client call, populates the attributes, builds the result and replies, so requests are served one at a time, end to end. This keeps attribute population in order per item and keeps lookups from overlapping a reclaim.
final class BridgeVolume: FSVolume {
	private struct Statistics {
		var totalBytes: UInt64 = 0
		var usableBytes: UInt64 = 0
	}

	private static let blockSize = 4096

	private let client: BridgeClient
	private let queue = DispatchQueue(label: "org.cryptomator.fskit.volume")
	private let registry = ItemRegistry()
	private let statistics = OSAllocatedUnfairLock(initialState: Statistics())
	private let fileSystemTypeName: String

	init(uuid: UUID, name: String, client: BridgeClient) {
		self.client = client
		let extensionAttributes = Bundle.main.object(forInfoDictionaryKey: "EXAppExtensionAttributes") as? [String: Any]
		self.fileSystemTypeName = extensionAttributes?["FSShortName"] as? String ?? ""
		super.init(volumeID: FSVolume.Identifier(uuid: uuid), volumeName: FSFileName(string: name))
	}

	/// Ends the connection once the requests already queued have been served.
	func disconnect() {
		queue.async {
			self.client.disconnect()
		}
	}

	// MARK: - Serving requests

	/// Runs `operation` on the volume's queue and replies with its result or the error it throws.
	private func serve<Result>(_ reply: @escaping (Result?, (any Error)?) -> Void, _ operation: @escaping () throws -> Result?) {
		queue.async {
			do {
				guard let result = try operation() else {
					// a result initializer refused the values it was given
					throw fs_errorForPOSIXError(POSIXError.EIO.rawValue)
				}
				reply(result, nil)
			} catch {
				reply(nil, self.fsError(error))
			}
		}
	}

	private func serve(_ reply: @escaping ((any Error)?) -> Void, _ operation: @escaping () throws -> Void) {
		queue.async {
			do {
				try operation()
				reply(nil)
			} catch {
				reply(self.fsError(error))
			}
		}
	}

	private func fsError(_ error: any Error) -> any Error {
		guard let failure = error as? StatusError else {
			return error
		}
		return failure.status == Messages.statusInvalidCookie ? FSError(.invalidDirectoryCookie) : fs_errorForPOSIXError(failure.status)
	}

	/// - Throws: `EACCES` unless the caller is root or the user the extension runs as, who is the one who mounted the volume. The volume is mounted with `noowners`, under which the kernel treats every caller as the owner and keeps nobody out.
	private func authorize(_ context: FSContext) throws {
		// root has to pass, since the final step of `mount` reaches the volume as uid 0
		guard context.effectiveUserID == Int(getuid()) || context.effectiveUserID == 0 else {
			throw fs_errorForPOSIXError(POSIXError.EACCES.rawValue)
		}
	}

	private func nodeId(_ item: FSItem) throws -> UInt64 {
		guard let item = item as? BridgeItem else {
			throw fs_errorForPOSIXError(POSIXError.EIO.rawValue)
		}
		return item.nodeId
	}

	private func string(_ name: FSFileName) throws -> String {
		guard let string = name.string else {
			throw fs_errorForPOSIXError(POSIXError.EINVAL.rawValue)
		}
		return string
	}

	// MARK: - Projections

	private func fsAttributes(_ attributes: Attributes) -> FSItem.Attributes {
		let result = FSItem.Attributes()
		result.fileID = FSItem.Identifier(attributes.nodeId)
		result.parentID = FSItem.Identifier(attributes.parentId)
		result.type = itemType(attributes.type)
		result.uid = getuid()
		result.gid = getgid()
		result.flags = 0
		result.size = attributes.size
		result.allocSize = attributes.size
		switch attributes.type {
		case .directory:
			result.linkCount = 2
			result.mode = UInt32(attributes.mode) | UInt32(S_IFDIR)
		case .symlink:
			result.linkCount = 1
			result.mode = UInt32(attributes.mode) | UInt32(S_IFLNK)
		case .file:
			result.linkCount = 1
			result.mode = UInt32(attributes.mode) | UInt32(S_IFREG)
		}
		result.modifyTime = timespec(attributes.modified)
		result.changeTime = timespec(attributes.modified)
		result.accessTime = timespec(attributes.accessed)
		result.birthTime = timespec(attributes.created)
		return result
	}

	private func itemType(_ type: NodeType) -> FSItem.ItemType {
		switch type {
		case .file: .file
		case .directory: .directory
		case .symlink: .symlink
		}
	}

	private func timespec(_ timestamp: Timestamp) -> Darwin.timespec {
		Darwin.timespec(tv_sec: Int(timestamp.seconds), tv_nsec: Int(timestamp.nanos))
	}

	/// - Throws: `EINVAL` for nanoseconds outside of a second. Callers may pass any value, and converting one that does not fit would trap.
	private func timestamp(_ time: Darwin.timespec) throws -> Timestamp {
		guard let nanos = UInt32(exactly: time.tv_nsec), nanos < 1_000_000_000 else {
			throw fs_errorForPOSIXError(POSIXError.EINVAL.rawValue)
		}
		return Timestamp(seconds: Int64(time.tv_sec), nanos: nanos)
	}

	private func record(usableBytes: UInt64) {
		if usableBytes != Messages.unknownUsableBytes {
			statistics.withLock { $0.usableBytes = usableBytes }
		}
	}

	/// Records the usable space a reply carried and hands it on to FSKit. Called on the queue, which is the isolation context of the volume's free space.
	private func freeSpace(_ usableBytes: UInt64) -> FSFreeSpace {
		guard usableBytes != Messages.unknownUsableBytes else {
			return FSFreeSpace.noUpdate
		}
		record(usableBytes: usableBytes)
		let freeSpace = FSFreeSpace()
		freeSpace.populate(bytes: usableBytes)
		return freeSpace
	}
}

extension BridgeVolume: FSVolume.PathConfOperations {
	var maximumLinkCount: Int {
		1
	}

	var maximumNameLength: Int {
		255
	}

	var restrictsOwnershipChanges: Bool {
		true
	}

	var truncatesLongNames: Bool {
		false
	}

	var maximumXattrSize: Int {
		// no effect was observed: macOS refuses the same attribute sizes whatever is declared here
		0
	}

	var maximumFileSize: UInt64 {
		// the server takes Int64 sizes
		UInt64(Int64.max)
	}
}

extension BridgeVolume: FSVolume.Handler {
	var supportedVolumeCapabilities: FSVolume.SupportedCapabilities {
		let capabilities = FSVolume.SupportedCapabilities()
		capabilities.supportsHardLinks = false
		capabilities.supportsSymbolicLinks = true
		capabilities.supportsPersistentObjectIDs = false
		capabilities.supports2TBFiles = true
		capabilities.doesNotSupportImmutableFiles = true
		capabilities.caseFormat = .sensitive
		return capabilities
	}

	var volumeStatistics: FSStatFSResult {
		// read from a snapshot, since this property must not wait for a request in flight
		let snapshot = statistics.withLock { $0 }
		let result = FSStatFSResult(fileSystemTypeName: fileSystemTypeName)
		result.blockSize = Self.blockSize
		result.ioSize = FrameCodec.maxPayloadLength
		result.totalBytes = snapshot.totalBytes
		result.availableBytes = snapshot.usableBytes
		result.freeBytes = snapshot.usableBytes
		result.usedBytes = snapshot.totalBytes >= snapshot.usableBytes ? snapshot.totalBytes - snapshot.usableBytes : 0
		return result
	}

	func activateVolume(options: FSTaskOptions, replyHandler reply: @escaping (FSActivateResult?, (any Error)?) -> Void) {
		serve(reply) {
			let response = try self.client.request(StatfsRequest())
			self.statistics.withLock { $0 = Statistics(totalBytes: response.totalBytes, usableBytes: response.usableBytes) }
			return FSActivateResult(rootItem: self.registry.item(for: Messages.rootNodeId))
		}
	}

	func deactivateVolume(options: FSDeactivateOptions = [], replyHandler reply: @escaping ((any Error)?) -> Void) {
		serve(reply) {}
	}

	func mount(options: FSTaskOptions, replyHandler reply: @escaping ((any Error)?) -> Void) {
		serve(reply) {}
	}

	func unmount(replyHandler reply: @escaping () -> Void) {
		queue.async {
			reply()
		}
	}

	func synchronize(flags: FSSyncFlags, replyHandler reply: @escaping ((any Error)?) -> Void) {
		serve(reply) {
			try self.record(usableBytes: self.client.request(SyncRequest()).usableBytes)
		}
	}

	func lookupItem(named name: FSFileName, in directory: FSItem, context: FSContext, replyHandler reply: @escaping (FSLookupItemResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			let response = try self.client.request(LookupRequest(parentId: self.nodeId(directory), name: self.string(name)))
			return FSLookupItemResult(foundItem: self.registry.item(for: response.attributes.nodeId), itemName: FSFileName(string: response.name), itemAttributes: self.fsAttributes(response.attributes))
		}
	}

	func reclaimItem(_ item: FSItem, replyHandler reply: @escaping ((any Error)?) -> Void) {
		serve(reply) {
			guard let item = item as? BridgeItem else {
				return
			}
			var failure: (any Error)?
			// FORGET is sent only from inside a reclaim FSKit agreed to, so no lookup of the item can be outstanding
			_ = item.tryReclaim {
				self.registry.remove(item)
				do {
					try self.record(usableBytes: self.client.request(ForgetRequest(nodeId: item.nodeId)).usableBytes)
				} catch {
					failure = error
				}
			}
			if let failure {
				throw failure
			}
		}
	}

	// swiftlint:disable:next function_parameter_count
	func createItem(named name: FSFileName, type: FSItem.ItemType, in directory: FSItem, attributes newAttributes: FSItem.SetAttributesRequest, context: FSContext, replyHandler reply: @escaping (FSCreateItemResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			let nodeType: NodeType
			switch type {
			case .file: nodeType = .file
			case .directory: nodeType = .directory
			default: throw fs_errorForPOSIXError(POSIXError.ENOTSUP.rawValue)
			}
			let mode = newAttributes.isValid(.mode) ? UInt16(newAttributes.mode & 0o777) : (nodeType == .directory ? 0o755 : 0o644)
			let response = try self.client.request(CreateRequest(parentId: self.nodeId(directory), name: self.string(name), type: nodeType, mode: mode))
			if newAttributes.isValid(.mode) {
				newAttributes.consumedAttributes.insert(.mode)
			}
			return FSCreateItemResult(
				newItem: self.registry.item(for: response.attributes.nodeId),
				newItemName: FSFileName(string: response.name),
				newItemAttributes: self.fsAttributes(response.attributes),
				directoryAttributes: self.fsAttributes(response.directoryAttributes),
				freeSpace: self.freeSpace(response.usableBytes)
			)
		}
	}

	// swiftlint:disable:next function_parameter_count
	func createSymbolicLink(named name: FSFileName, in directory: FSItem, attributes newAttributes: FSItem.SetAttributesRequest, linkContents contents: FSFileName, context: FSContext, replyHandler reply: @escaping (FSCreateSymlinkResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			// the mode is not marked as consumed, since the server applies none to a link
			let response = try self.client.request(SymlinkRequest(parentId: self.nodeId(directory), name: self.string(name), target: self.string(contents)))
			return FSCreateSymlinkResult(
				newItem: self.registry.item(for: response.attributes.nodeId),
				newItemName: FSFileName(string: response.name),
				newItemAttributes: self.fsAttributes(response.attributes),
				directoryAttributes: self.fsAttributes(response.directoryAttributes),
				freeSpace: self.freeSpace(response.usableBytes)
			)
		}
	}

	func createLink(to item: FSItem, named name: FSFileName, in directory: FSItem, context: FSContext, replyHandler reply: @escaping (FSCreateLinkResult?, (any Error)?) -> Void) {
		reply(nil, fs_errorForPOSIXError(POSIXError.ENOTSUP.rawValue))
	}

	// swiftlint:disable:next function_parameter_count
	func renameItem(_ item: FSItem, inDirectory sourceDirectory: FSItem, named sourceName: FSFileName, to destinationName: FSFileName, inDirectory destinationDirectory: FSItem, overItem: FSItem?, context: FSContext, replyHandler reply: @escaping (FSRenameItemResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			let response = try self.client.request(RenameRequest(nodeId: self.nodeId(item), sourceParentId: self.nodeId(sourceDirectory), destinationParentId: self.nodeId(destinationDirectory), destinationName: self.string(destinationName)))
			return FSRenameItemResult(
				newName: FSFileName(string: response.name),
				renamedItemAttributes: self.fsAttributes(response.attributes),
				sourceDirectoryAttributes: self.fsAttributes(response.sourceDirectoryAttributes),
				destinationDirectoryAttributes: self.fsAttributes(response.destinationDirectoryAttributes),
				overItemAttributes: overItem == nil ? nil : response.replacedAttributes.map { self.fsAttributes($0) },
				freeSpace: self.freeSpace(response.usableBytes)
			)
		}
	}

	func removeItem(_ item: FSItem, named name: FSFileName, from directory: FSItem, context: FSContext, replyHandler reply: @escaping (FSRemoveItemResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			let response = try self.client.request(RemoveRequest(nodeId: self.nodeId(item), parentId: self.nodeId(directory)))
			return FSRemoveItemResult(
				itemAttributes: self.fsAttributes(response.attributes),
				directoryAttributes: self.fsAttributes(response.directoryAttributes),
				freeSpace: self.freeSpace(response.usableBytes)
			)
		}
	}

	func getAttributes(_ desiredAttributes: FSItem.GetAttributesRequest, of item: FSItem, context: FSContext, replyHandler reply: @escaping (FSGetAttributesResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			let response = try self.client.request(GetattrRequest(nodeId: self.nodeId(item)))
			return FSGetAttributesResult(attributes: self.fsAttributes(response.attributes))
		}
	}

	func setAttributes(_ newAttributes: FSItem.SetAttributesRequest, on item: FSItem, context: FSContext, replyHandler reply: @escaping (FSSetAttributesResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			let settable: [(FSItem.Attribute, UInt8)] = [(.size, Messages.attributeSize), (.mode, Messages.attributeMode), (.accessTime, Messages.attributeAccessed), (.modifyTime, Messages.attributeModified)]
			let valid = settable.filter { newAttributes.isValid($0.0) }.reduce(0) { $0 | $1.1 }
			let response = try self.client.request(SetattrRequest(
				nodeId: self.nodeId(item),
				valid: valid,
				size: newAttributes.size,
				mode: UInt16(newAttributes.mode & 0o777),
				accessed: newAttributes.isValid(.accessTime) ? self.timestamp(newAttributes.accessTime) : Timestamp(seconds: 0, nanos: 0),
				modified: newAttributes.isValid(.modifyTime) ? self.timestamp(newAttributes.modifyTime) : Timestamp(seconds: 0, nanos: 0)
			))
			for (attribute, bit) in settable where response.applied & bit != 0 {
				newAttributes.consumedAttributes.insert(attribute)
			}
			return FSSetAttributesResult(attributes: self.fsAttributes(response.attributes), freeSpace: self.freeSpace(response.usableBytes))
		}
	}

	// swiftlint:disable:next function_parameter_count
	func enumerateDirectory(_ directory: FSItem, startingAt cookie: FSDirectoryCookie, verifier: FSDirectoryVerifier, attributes: FSItem.GetAttributesRequest?, packer: FSDirectoryEntryPacker, context: FSContext, replyHandler reply: @escaping (FSEnumerateDirectoryResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			var cookie = cookie.rawValue
			var verifier = verifier.rawValue
			var morePages = true
			while morePages {
				let page = try self.client.request(ReaddirRequest(nodeId: self.nodeId(directory), cookie: cookie, verifier: verifier, wantAttributes: attributes != nil))
				verifier = page.verifier
				morePages = page.more && !page.entries.isEmpty
				for entry in page.entries {
					let packed = packer.packEntry(
						name: FSFileName(string: entry.name),
						itemType: self.itemType(entry.type),
						itemID: FSItem.Identifier(entry.nodeId),
						nextCookie: FSDirectoryCookie(entry.nextCookie),
						attributes: entry.attributes.map { self.fsAttributes($0) }
					)
					guard packed else {
						morePages = false
						break
					}
					cookie = entry.nextCookie
				}
			}
			return FSEnumerateDirectoryResult(verifier: verifier)
		}
	}

	func readSymbolicLink(_ item: FSItem, context: FSContext, replyHandler reply: @escaping (FSReadSymlinkResult?, (any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			let response = try self.client.request(ReadlinkRequest(nodeId: self.nodeId(item)))
			return FSReadSymlinkResult(contents: FSFileName(string: response.target), symlinkAttributes: self.fsAttributes(response.attributes))
		}
	}
}

extension BridgeVolume: FSVolume.OpenCloseHandler {
	private func modes(_ modes: FSVolume.OpenModes) -> UInt8 {
		(modes.contains(.read) ? Messages.modeRead : 0) | (modes.contains(.write) ? Messages.modeWrite : 0)
	}

	func openItem(_ item: FSItem, modes: FSVolume.OpenModes, context: FSContext, replyHandler reply: @escaping ((any Error)?) -> Void) {
		serve(reply) {
			try self.authorize(context)
			_ = try self.client.request(OpenRequest(nodeId: self.nodeId(item), modes: self.modes(modes)))
		}
	}

	func closeItem(_ item: FSItem, modes: FSVolume.OpenModes, context: FSContext, replyHandler reply: @escaping ((any Error)?) -> Void) {
		serve(reply) {
			// no caller is refused here: a close that does not reach the server leaves its channel open
			try self.record(usableBytes: self.client.request(CloseRequest(nodeId: self.nodeId(item), keptModes: self.modes(modes))).usableBytes)
		}
	}
}

extension BridgeVolume: FSVolume.ReadWriteHandler {
	func read(from item: FSItem, at offset: off_t, length: Int, into buffer: FSMutableFileDataBuffer, replyHandler reply: @escaping (FSReadFileResult?, (any Error)?) -> Void) {
		serve(reply) {
			let response = try self.client.read(nodeId: self.nodeId(item), offset: UInt64(offset), length: min(length, buffer.length))
			_ = buffer.withUnsafeMutableBytes { destination in
				response.data.copyBytes(to: destination)
			}
			return FSReadFileResult(bytesRead: response.data.count, itemAttributes: self.fsAttributes(response.attributes))
		}
	}

	func write(contents: Data, to item: FSItem, at offset: off_t, replyHandler reply: @escaping (FSWriteFileResult?, (any Error)?) -> Void) {
		serve(reply) {
			let response = try self.client.write(nodeId: self.nodeId(item), offset: UInt64(offset), data: contents)
			return FSWriteFileResult(bytesWritten: Int(response.written), itemAttributes: self.fsAttributes(response.attributes), freeSpace: self.freeSpace(response.usableBytes))
		}
	}
}

/// Accepts every extended attribute and stores none.
///
/// Without this conformance macOS keeps attributes in AppleDouble companion files named `._<name>` in the mounted `Path`. Refusing them is no alternative: Finder then copies nothing onto the volume.
///
/// The handlers cannot tell callers apart, since FSKit calls them with uid 0 for every caller.
extension BridgeVolume: FSVolume.XattrHandler {
	func getXattr(named name: FSFileName, of item: FSItem, context: FSContext, replyHandler reply: @escaping (FSGetXattrResult?, (any Error)?) -> Void) {
		reply(nil, fs_errorForPOSIXError(POSIXError.ENOATTR.rawValue))
	}

	// swiftlint:disable:next function_parameter_count
	func setXattr(named name: FSFileName, to value: Data?, on item: FSItem, policy: FSVolume.SetXattrPolicy, context: FSContext, replyHandler reply: @escaping (FSSetXattrResult?, (any Error)?) -> Void) {
		// nothing is stored, so there is never an attribute to replace or to remove
		if policy == .mustReplace || policy == .delete {
			reply(nil, fs_errorForPOSIXError(POSIXError.ENOATTR.rawValue))
			return
		}
		reply(FSSetXattrResult(freeSpace: FSFreeSpace.noUpdate), nil)
	}

	func listXattrs(of item: FSItem, context: FSContext, replyHandler reply: @escaping (FSListXattrsResult?, (any Error)?) -> Void) {
		reply(FSListXattrsResult(xattrNames: []), nil)
	}
}
