import Foundation
import FSKit
import FSKitBridge
import os

/// Forwards the operations it supports to the server of the mount.
///
/// Handlers run concurrently, each awaiting its own requests. One lock guards the volume's state: the items handed to FSKit with their lookups, the newest attributes and free space seen, and the volume's statistics. No request is sent while it is held.
///
/// - Attributes and free space are populated under it, from the newest record by the server's generation, so that FSKit never takes an older record for the newest.
/// - The handlers that hand out an item reply under it, and a reclaim tries the item under it, so that a reclaim never comes between handing out an item and FSKit counting it.
/// - Every handler that sends a request holds a ticket of the attribute ledger from its start until it has populated, which keeps the records it may still populate.
final class BridgeVolume: FSVolume, @unchecked Sendable {
	private struct Statistics {
		var totalBytes: UInt64 = 0
		var usableBytes: UInt64 = 0
	}

	private struct State {
		var items = ItemTable(make: BridgeItem.init(nodeId:))
		var ledger = AttributeLedger()
		var statistics = Statistics()
		/// Replies to an unmount, made once no handler holds a ticket.
		var awaitingIdle: [@Sendable () -> Void] = []
	}

	private static let blockSize = 4096

	private let client: BridgeClient
	private let lister: DirectoryLister
	private let state = OSAllocatedUnfairLock(uncheckedState: State())
	private let fileSystemTypeName: String

	init(uuid: UUID, name: String, client: BridgeClient) {
		self.client = client
		self.lister = DirectoryLister(client: client)
		let extensionAttributes = Bundle.main.object(forInfoDictionaryKey: "EXAppExtensionAttributes") as? [String: Any]
		self.fileSystemTypeName = extensionAttributes?["FSShortName"] as? String ?? ""
		super.init(volumeID: FSVolume.Identifier(uuid: uuid), volumeName: FSFileName(string: name))
	}

	/// Ends the connection. Requests in flight fail.
	func disconnect() {
		client.disconnect()
	}

	// MARK: - Serving requests

	private func begin() -> AttributeLedger.Ticket {
		state.withLockUnchecked { $0.ledger.begin() }
	}

	private func finish(_ ticket: AttributeLedger.Ticket) {
		let awaitingIdle = state.withLockUnchecked { state in
			state.ledger.finish(ticket)
			guard state.ledger.isIdle else {
				return [@Sendable () -> Void]()
			}
			defer { state.awaitingIdle = [] }
			return state.awaitingIdle
		}
		awaitingIdle.forEach { $0() }
	}

	/// Runs an operation under a ticket of the ledger and maps what it throws to the error FSKit expects.
	private func serve<Result>(_ operation: () async throws -> Result) async throws -> Result {
		let ticket = begin()
		defer { finish(ticket) }
		do {
			return try await operation()
		} catch {
			throw fsError(error)
		}
	}

	/// Serves a handler that hands out an item. It replies under the state lock, which only the reply-handler form allows. FSKit counts a returned item once the reply is made, so neither a reclaim of the item nor a lookup of it may come between.
	///
	/// - Parameters:
	///   - prepare: Runs at once, under the handler's ticket: checks what needs no request and returns what the request needs, since the handler's arguments cannot go to the task that sends it.
	///   - operation: Sends the request and returns how to build the result from the state.
	private func serveItem<Prepared: Sendable, Result>(_ reply: @escaping @Sendable (Result?, (any Error)?) -> Void, prepare: () throws -> Prepared, _ operation: @escaping @Sendable (Prepared) async throws -> (inout State) throws -> Result?) {
		let ticket = begin()
		let prepared: Prepared
		do {
			prepared = try prepare()
		} catch {
			reply(nil, fsError(error))
			finish(ticket)
			return
		}
		Task {
			defer { self.finish(ticket) }
			do {
				let build = try await operation(prepared)
				try self.state.withLockUnchecked { state in
					try reply(self.required(build(&state)), nil)
				}
			} catch {
				reply(nil, self.fsError(error))
			}
		}
	}

	/// - Throws: `EIO` for `nil`, which a result initializer returns when it refuses the values it was given.
	private func required<Result>(_ result: Result?) throws -> Result {
		guard let result else {
			throw fs_errorForPOSIXError(POSIXError.EIO.rawValue)
		}
		return result
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

	/// Populates FSKit's attributes from the newest record of the item. Called under the state lock.
	private func fsAttributes(_ received: Attributes, _ state: inout State) -> FSItem.Attributes {
		let attributes = state.ledger.newest(received)
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

	/// Records a sample of the usable space if it is the newest.
	private func record(_ freeSpace: FreeSpace) {
		state.withLockUnchecked { state in
			_ = self.newestUsableBytes(freeSpace, &state)
		}
	}

	private func newestUsableBytes(_ freeSpace: FreeSpace, _ state: inout State) -> UInt64? {
		guard let usableBytes = state.ledger.newest(freeSpace) else {
			return nil
		}
		state.statistics.usableBytes = usableBytes
		return usableBytes
	}

	/// Records a sample of the usable space and hands it on to FSKit if it is the newest. Called under the state lock, which is the isolation context of the volume's free space.
	private func fsFreeSpace(_ freeSpace: FreeSpace, _ state: inout State) -> FSFreeSpace {
		guard let usableBytes = newestUsableBytes(freeSpace, &state) else {
			return FSFreeSpace.noUpdate
		}
		let result = FSFreeSpace()
		result.populate(bytes: usableBytes)
		return result
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
		let snapshot = state.withLockUnchecked { $0.statistics }
		let result = FSStatFSResult(fileSystemTypeName: fileSystemTypeName)
		result.blockSize = Self.blockSize
		result.ioSize = FrameCodec.maxPayloadLength
		result.totalBytes = snapshot.totalBytes
		result.availableBytes = snapshot.usableBytes
		result.freeBytes = snapshot.usableBytes
		result.usedBytes = snapshot.totalBytes >= snapshot.usableBytes ? snapshot.totalBytes - snapshot.usableBytes : 0
		return result
	}

	func activateVolume(options: FSTaskOptions) async throws -> FSActivateResult {
		try await serve {
			let response = try await client.request(StatfsRequest())
			return try state.withLockUnchecked { state in
				state.statistics.totalBytes = response.totalBytes
				_ = newestUsableBytes(response.freeSpace, &state)
				return try required(FSActivateResult(rootItem: state.items.item(for: Messages.rootNodeId)))
			}
		}
	}

	func deactivateVolume(options: FSDeactivateOptions = []) async throws {}

	func mount(options: FSTaskOptions) async throws {}

	func unmount(replyHandler reply: @escaping @Sendable () -> Void) {
		// once the handlers in flight are done
		let idle = state.withLockUnchecked { state in
			if !state.ledger.isIdle {
				state.awaitingIdle.append(reply)
			}
			return state.ledger.isIdle
		}
		if idle {
			reply()
		}
	}

	func synchronize(flags: FSSyncFlags) async throws {
		try await serve {
			// FSKit asks before every reclaim, mostly with nothing to force
			guard !client.canAnswerSyncItself else {
				return
			}
			try await record(client.request(SyncRequest()).freeSpace)
		}
	}

	func lookupItem(named name: FSFileName, in directory: FSItem, context: FSContext, replyHandler reply: @escaping @Sendable (FSLookupItemResult?, (any Error)?) -> Void) {
		serveItem(reply) {
			try authorize(context)
			let string = try string(name)
			// macOS looks up `._<name>` after every first lookup of an entry. The server answers a hidden name with ENOENT as well, so the request is not sent.
			if HiddenNames.contains(string) {
				throw fs_errorForPOSIXError(POSIXError.ENOENT.rawValue)
			}
			return try LookupRequest(parentId: nodeId(directory), name: string)
		} _: { request in
			let response = try await self.client.request(request)
			return { state in
				let item = state.items.item(for: response.attributes.nodeId)
				return FSLookupItemResult(foundItem: item, itemName: FSFileName(string: response.name), itemAttributes: self.fsAttributes(response.attributes, &state))
			}
		}
	}

	func reclaimItem(_ item: FSItem) async throws {
		try await serve {
			guard let item = item as? BridgeItem else {
				return
			}
			let lookups = state.withLockUnchecked { state -> UInt64? in
				// FSKit runs the block only if no lookup has returned the item since it asked for the reclaim, and none can while the lock is held
				guard item.tryReclaim({}) else {
					return nil
				}
				return state.items.removeIfCurrent(item, nodeId: item.nodeId)
			}
			guard let lookups else {
				return
			}
			try await record(client.request(ForgetRequest(nodeId: item.nodeId, lookups: lookups)).freeSpace)
		}
	}

	// swiftlint:disable:next function_parameter_count
	func createItem(named name: FSFileName, type: FSItem.ItemType, in directory: FSItem, attributes newAttributes: FSItem.SetAttributesRequest, context: FSContext, replyHandler reply: @escaping @Sendable (FSCreateItemResult?, (any Error)?) -> Void) {
		serveItem(reply) {
			try authorize(context)
			let nodeType: NodeType
			switch type {
			case .file: nodeType = .file
			case .directory: nodeType = .directory
			default: throw fs_errorForPOSIXError(POSIXError.ENOTSUP.rawValue)
			}
			let mode = newAttributes.isValid(.mode) ? UInt16(newAttributes.mode & 0o777) : (nodeType == .directory ? 0o755 : 0o644)
			let request = try CreateRequest(parentId: nodeId(directory), name: string(name), type: nodeType, mode: mode)
			// marked before the request is sent, since the arguments cannot go to the task that sends it. A create that fails replies with no result.
			if newAttributes.isValid(.mode) {
				newAttributes.consumedAttributes.insert(.mode)
			}
			return request
		} _: { request in
			let response = try await self.client.request(request)
			return { state in
				let item = state.items.item(for: response.attributes.nodeId)
				if request.type == .file {
					item.openedByCreate = true
				}
				return FSCreateItemResult(
					newItem: item,
					newItemName: FSFileName(string: response.name),
					newItemAttributes: self.fsAttributes(response.attributes, &state),
					directoryAttributes: self.fsAttributes(response.directoryAttributes, &state),
					freeSpace: self.fsFreeSpace(response.freeSpace, &state)
				)
			}
		}
	}

	// swiftlint:disable:next function_parameter_count
	func createSymbolicLink(named name: FSFileName, in directory: FSItem, attributes newAttributes: FSItem.SetAttributesRequest, linkContents contents: FSFileName, context: FSContext, replyHandler reply: @escaping @Sendable (FSCreateSymlinkResult?, (any Error)?) -> Void) {
		serveItem(reply) {
			try authorize(context)
			// the mode is not marked as consumed, since the server applies none to a link
			return try SymlinkRequest(parentId: nodeId(directory), name: string(name), target: string(contents))
		} _: { request in
			let response = try await self.client.request(request)
			return { state in
				FSCreateSymlinkResult(
					newItem: state.items.item(for: response.attributes.nodeId),
					newItemName: FSFileName(string: response.name),
					newItemAttributes: self.fsAttributes(response.attributes, &state),
					directoryAttributes: self.fsAttributes(response.directoryAttributes, &state),
					freeSpace: self.fsFreeSpace(response.freeSpace, &state)
				)
			}
		}
	}

	func createLink(to item: FSItem, named name: FSFileName, in directory: FSItem, context: FSContext, replyHandler reply: @escaping @Sendable (FSCreateLinkResult?, (any Error)?) -> Void) {
		reply(nil, fs_errorForPOSIXError(POSIXError.ENOTSUP.rawValue))
	}

	// swiftlint:disable:next function_parameter_count
	func renameItem(_ item: FSItem, inDirectory sourceDirectory: FSItem, named sourceName: FSFileName, to destinationName: FSFileName, inDirectory destinationDirectory: FSItem, overItem: FSItem?, context: FSContext) async throws -> FSRenameItemResult {
		try await serve {
			try authorize(context)
			let response = try await client.request(RenameRequest(nodeId: nodeId(item), sourceParentId: nodeId(sourceDirectory), sourceName: string(sourceName), destinationParentId: nodeId(destinationDirectory), destinationName: string(destinationName)))
			return try state.withLockUnchecked { state in
				try required(FSRenameItemResult(
					newName: FSFileName(string: response.name),
					renamedItemAttributes: fsAttributes(response.attributes, &state),
					sourceDirectoryAttributes: fsAttributes(response.sourceDirectoryAttributes, &state),
					destinationDirectoryAttributes: fsAttributes(response.destinationDirectoryAttributes, &state),
					overItemAttributes: overItem == nil ? nil : response.replacedAttributes.map { fsAttributes($0, &state) },
					freeSpace: fsFreeSpace(response.freeSpace, &state)
				))
			}
		}
	}

	func removeItem(_ item: FSItem, named name: FSFileName, from directory: FSItem, context: FSContext) async throws -> FSRemoveItemResult {
		try await serve {
			try authorize(context)
			let response = try await client.request(RemoveRequest(nodeId: nodeId(item), parentId: nodeId(directory), name: string(name)))
			return try state.withLockUnchecked { state in
				try required(FSRemoveItemResult(
					itemAttributes: fsAttributes(response.attributes, &state),
					directoryAttributes: fsAttributes(response.directoryAttributes, &state),
					freeSpace: fsFreeSpace(response.freeSpace, &state)
				))
			}
		}
	}

	func attributes(_ desiredAttributes: FSItem.GetAttributesRequest, of item: FSItem, context: FSContext) async throws -> FSGetAttributesResult {
		try await serve {
			try authorize(context)
			let response = try await client.request(GetattrRequest(nodeId: nodeId(item)))
			return try state.withLockUnchecked { state in
				try required(FSGetAttributesResult(attributes: fsAttributes(response.attributes, &state)))
			}
		}
	}

	func setAttributes(_ newAttributes: FSItem.SetAttributesRequest, on item: FSItem, context: FSContext) async throws -> FSSetAttributesResult {
		try await serve {
			try authorize(context)
			let settable: [(FSItem.Attribute, UInt8)] = [(.size, Messages.attributeSize), (.mode, Messages.attributeMode), (.accessTime, Messages.attributeAccessed), (.modifyTime, Messages.attributeModified)]
			let valid = settable.filter { newAttributes.isValid($0.0) }.reduce(0) { $0 | $1.1 }
			let response = try await client.request(SetattrRequest(
				nodeId: nodeId(item),
				valid: valid,
				size: newAttributes.size,
				mode: UInt16(newAttributes.mode & 0o777),
				accessed: newAttributes.isValid(.accessTime) ? timestamp(newAttributes.accessTime) : Timestamp(seconds: 0, nanos: 0),
				modified: newAttributes.isValid(.modifyTime) ? timestamp(newAttributes.modifyTime) : Timestamp(seconds: 0, nanos: 0)
			))
			for (attribute, bit) in settable where response.applied & bit != 0 {
				newAttributes.consumedAttributes.insert(attribute)
			}
			return try state.withLockUnchecked { state in
				try required(FSSetAttributesResult(attributes: fsAttributes(response.attributes, &state), freeSpace: fsFreeSpace(response.freeSpace, &state)))
			}
		}
	}

	// swiftlint:disable:next function_parameter_count
	func enumerateDirectory(_ directory: FSItem, startingAt cookie: FSDirectoryCookie, verifier: FSDirectoryVerifier, attributes: FSItem.GetAttributesRequest?, packer: FSDirectoryEntryPacker, context: FSContext) async throws -> FSEnumerateDirectoryResult {
		try await serve {
			try authorize(context)
			let verifier = try await lister.list(directory: nodeId(directory), cookie: cookie.rawValue, verifier: verifier.rawValue, wantAttributes: attributes != nil) { entry in
				let attributes = entry.attributes.map { received in
					state.withLockUnchecked { fsAttributes(received, &$0) }
				}
				return packer.packEntry(
					name: FSFileName(string: entry.name),
					itemType: itemType(entry.type),
					itemID: FSItem.Identifier(entry.nodeId),
					nextCookie: FSDirectoryCookie(entry.nextCookie),
					attributes: attributes
				)
			}
			return try required(FSEnumerateDirectoryResult(verifier: verifier))
		}
	}

	func readSymbolicLink(_ item: FSItem, context: FSContext) async throws -> FSReadSymlinkResult {
		try await serve {
			try authorize(context)
			let response = try await client.request(ReadlinkRequest(nodeId: nodeId(item)))
			return try state.withLockUnchecked { state in
				try required(FSReadSymlinkResult(contents: FSFileName(string: response.target), symlinkAttributes: fsAttributes(response.attributes, &state)))
			}
		}
	}
}

extension BridgeVolume: FSVolume.OpenCloseHandler {
	private func modes(_ modes: FSVolume.OpenModes) -> UInt8 {
		(modes.contains(.read) ? Messages.modeRead : 0) | (modes.contains(.write) ? Messages.modeWrite : 0)
	}

	func openItem(_ item: FSItem, modes: FSVolume.OpenModes, context: FSContext) async throws {
		try await serve {
			try authorize(context)
			let openedByCreate = state.withLockUnchecked { _ in (item as? BridgeItem)?.openedByCreate == true }
			if openedByCreate {
				return
			}
			_ = try await client.request(OpenRequest(nodeId: nodeId(item), modes: self.modes(modes)))
		}
	}

	func closeItem(_ item: FSItem, modes: FSVolume.OpenModes, context: FSContext) async throws {
		try await serve {
			// no caller is refused here: a close that does not reach the server leaves its channel open
			let keptModes = self.modes(modes)
			if keptModes == 0 {
				// before the CLOSE is sent, so that an open from then on is forwarded and reopens the channel
				state.withLockUnchecked { _ in (item as? BridgeItem)?.openedByCreate = false }
			}
			try await record(client.request(CloseRequest(nodeId: nodeId(item), keptModes: keptModes)).freeSpace)
		}
	}
}

extension BridgeVolume: FSVolume.ReadWriteHandler {
	func read(from item: FSItem, at offset: off_t, length: Int, into buffer: FSMutableFileDataBuffer) async throws -> FSReadFileResult {
		try await serve {
			let response = try await client.read(nodeId: nodeId(item), offset: UInt64(offset), length: min(length, buffer.length))
			_ = buffer.withUnsafeMutableBytes { destination in
				response.data.copyBytes(to: destination)
			}
			return try state.withLockUnchecked { state in
				try required(FSReadFileResult(bytesRead: response.data.count, itemAttributes: fsAttributes(response.attributes, &state)))
			}
		}
	}

	func write(contents: Data, to item: FSItem, at offset: off_t) async throws -> FSWriteFileResult {
		try await serve {
			let response = try await client.write(nodeId: nodeId(item), offset: UInt64(offset), data: contents)
			return try state.withLockUnchecked { state in
				try required(FSWriteFileResult(bytesWritten: Int(response.written), itemAttributes: fsAttributes(response.attributes, &state), freeSpace: fsFreeSpace(response.freeSpace, &state)))
			}
		}
	}
}

/// Accepts every extended attribute and stores none.
///
/// Without this conformance macOS keeps attributes in AppleDouble companion files named `._<name>` in the mounted `Path`. Refusing them is no alternative: Finder then copies nothing onto the volume.
///
/// The handlers cannot tell callers apart, since FSKit calls them with uid 0 for every caller.
extension BridgeVolume: FSVolume.XattrHandler {
	func getXattr(named name: FSFileName, of item: FSItem, context: FSContext, replyHandler reply: @escaping @Sendable (FSGetXattrResult?, (any Error)?) -> Void) {
		reply(nil, fs_errorForPOSIXError(POSIXError.ENOATTR.rawValue))
	}

	// swiftlint:disable:next function_parameter_count
	func setXattr(named name: FSFileName, to value: Data?, on item: FSItem, policy: FSVolume.SetXattrPolicy, context: FSContext, replyHandler reply: @escaping @Sendable (FSSetXattrResult?, (any Error)?) -> Void) {
		// nothing is stored, so there is never an attribute to replace or to remove
		if policy == .mustReplace || policy == .delete {
			reply(nil, fs_errorForPOSIXError(POSIXError.ENOATTR.rawValue))
			return
		}
		reply(FSSetXattrResult(freeSpace: FSFreeSpace.noUpdate), nil)
	}

	func listXattrs(of item: FSItem, context: FSContext, replyHandler reply: @escaping @Sendable (FSListXattrsResult?, (any Error)?) -> Void) {
		reply(FSListXattrsResult(xattrNames: []), nil)
	}
}
