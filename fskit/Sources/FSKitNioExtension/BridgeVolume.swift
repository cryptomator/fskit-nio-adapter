import Foundation
import FSKit
import FSKitBridge
import os

/// Forwards the operations it supports to the server of the mount.
///
/// Handlers run concurrently, each awaiting its own requests. One lock guards the volume's state: the items handed to FSKit with their lookups, the newest attributes and free space seen, what FSKit's cache of each file and directory holds, and the volume's statistics. No request is sent and no cache state is set while it is held.
///
/// - Attributes and free space are populated under it, from the newest record by the server's generation, so that FSKit never takes an older record for the newest.
/// - The handlers that hand out an item reply under it, and a reclaim tries the item under it, so that a reclaim never comes between handing out an item and FSKit counting it.
/// - Every handler that sends a request holds a ticket of the attribute ledger from its start until it has populated, which keeps the records it may still populate.
/// - Every record of a file or directory goes to its `CacheValidity` under it. A file that changed outside the volume is revoked: FSKit drops what it cached and looks the item up again once nothing holds it open. A directory that changed outside is invalidated: FSKit drops the names it cached in it and keeps the item. Releases before macOS 27.2.1 accept the call and drop nothing. The root is left out, since FSKit refuses to drop what it cached of it.
/// - A revoke or an invalidation is claimed under the lock and runs outside it: from a task of its own that the handler which found it does not wait for, or in the task that watches for changes. A revoke may also run within the open or upgrade that found it. An open or upgrade replies with its grant under the lock, in the section that finds no revoke of the item due, running or done, so that no revoke starts between that check and the grant.
final class BridgeVolume: FSVolume, @unchecked Sendable {
	private struct Statistics {
		var totalBytes: UInt64 = 0
		var usableBytes: UInt64 = 0
	}

	private struct State {
		var items = ItemTable(make: BridgeItem.init(nodeId:))
		var ledger = AttributeLedger()
		var statistics = Statistics()
		var validity = CacheValidity<BridgeItem>()
		/// Replies to an unmount, made once no handler holds a ticket.
		var awaitingIdle: [@Sendable () -> Void] = []
	}

	private static let blockSize = 4096

	private let logger = Logger(subsystem: "org.cryptomator.fskit", category: "BridgeVolume")
	private let client: BridgeClient
	private let lister: DirectoryLister
	private let state = OSAllocatedUnfairLock(uncheckedState: State())
	private let fileSystemTypeName: String
	private let displayName: String

	init(uuid: UUID, name: String, client: BridgeClient) {
		self.client = client
		self.lister = DirectoryLister(client: client)
		let extensionAttributes = Bundle.main.object(forInfoDictionaryKey: "EXAppExtensionAttributes") as? [String: Any]
		self.fileSystemTypeName = extensionAttributes?["FSShortName"] as? String ?? ""
		self.displayName = name
		super.init(volumeID: FSVolume.Identifier(uuid: uuid), volumeName: FSFileName(string: MountPointName.of(name)))
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

	/// Runs an operation under a ticket of the ledger and maps what it throws to the error FSKit expects. Then starts the revokes and invalidations that are due.
	///
	/// - Parameter item: The item the operation is for. If the server no longer finds it, its revoke or invalidation is due.
	private func serve<Result>(for item: FSItem? = nil, _ operation: () async throws -> Result) async throws -> Result {
		let ticket = begin()
		defer {
			finish(ticket)
			dropDueCaches()
		}
		do {
			return try await operation()
		} catch {
			throw failure(error, for: item as? BridgeItem)
		}
	}

	/// Maps what an operation threw to the error FSKit expects. If the server no longer finds the item the operation was for, its revoke or invalidation is due.
	private func failure(_ error: any Error, for item: BridgeItem?) -> any Error {
		if let item, (error as? StatusError)?.status == ENOENT {
			state.withLockUnchecked { $0.validity.missing(item.nodeId) }
		}
		return fsError(error)
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
			defer {
				self.finish(ticket)
				self.dropDueCaches()
			}
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
		try bridgeItem(item).nodeId
	}

	private func bridgeItem(_ item: FSItem) throws -> BridgeItem {
		guard let item = item as? BridgeItem else {
			throw fs_errorForPOSIXError(POSIXError.EIO.rawValue)
		}
		return item
	}

	private func string(_ name: FSFileName) throws -> String {
		guard let string = name.string else {
			throw fs_errorForPOSIXError(POSIXError.EINVAL.rawValue)
		}
		return string
	}

	// MARK: - Revokes and invalidations

	/// Runs the revokes and invalidations that are due, from a task of their own, so that the handler that found them does not wait for them.
	private func dropDueCaches() {
		let claimed = state.withLockUnchecked { $0.validity.claimDue() }
		guard !claimed.isEmpty else {
			return
		}
		Task.detached {
			claimed.forEach(self.dropCache)
		}
	}

	/// Runs a claimed revoke or invalidation. Called without the state lock.
	private func dropCache(_ claimed: CacheValidity<BridgeItem>.Claimed) {
		switch claimed.action {
		case .revoke: revoke(claimed.item)
		case .invalidate: invalidate(claimed.item)
		}
	}

	/// Drops what FSKit cached of a file whose revoke was claimed. FSKit reclaims the item once nothing holds it open, and looks its name up again. Called without the state lock.
	private func revoke(_ item: BridgeItem) {
		let error = setCacheState(for: item, cacheMode: .none, coherencyType: .noCache, action: .revoke)
		if let error {
			logger.debug("Failed to revoke node \(item.nodeId, privacy: .public): \(String(describing: error), privacy: .public)")
		}
		state.withLockUnchecked { $0.validity.revokeEnded(item, nodeId: item.nodeId, success: error == nil) }
	}

	/// Drops the names FSKit cached in a directory whose invalidation was claimed, so that the next path walk looks them up again. FSKit keeps the item. A failed invalidation is due again and runs with the next handler or the next changes. Called without the state lock.
	private func invalidate(_ item: BridgeItem) {
		let error = setCacheState(for: item, cacheMode: .none, coherencyType: .noCache, action: .invalidate)
		if let error {
			logger.debug("Failed to invalidate node \(item.nodeId, privacy: .public): \(String(describing: error), privacy: .public)")
		}
		state.withLockUnchecked { $0.validity.invalidationEnded(item, nodeId: item.nodeId, success: error == nil) }
	}

	/// Asks the server for changes made outside the volume, and revokes the files and invalidates the directories they concern. Ends once the server has no source of them or the connection is lost, as at every unmount. Holds no ticket of the ledger, since it populates nothing.
	private func watchChanges() async {
		while true {
			do {
				let response = try await client.request(ChangesRequest())
				let claimed = state.withLockUnchecked { state in
					response.attributes.forEach { state.validity.compare($0, .locked) }
					return state.validity.claimDue()
				}
				claimed.forEach(dropCache)
			} catch let failure as StatusError where failure.status != ENOTSUP && client.isConnected {
				// an unexpected failure of the server, which the next attempt may not meet
				try? await Task.sleep(for: .seconds(1))
			} catch {
				return
			}
		}
	}

	// MARK: - Projections

	/// Populates FSKit's attributes from the newest record of the item, and compares the received record with what FSKit's cache holds. Called under the state lock.
	///
	/// - Parameter reading: How the server read the record. A reply that does not name it may have been sampled.
	private func fsAttributes(_ received: Attributes, _ state: inout State, _ reading: CacheValidity<BridgeItem>.Reading = .sampled) -> FSItem.Attributes {
		state.validity.compare(received, reading)
		return populated(received, &state)
	}

	/// Populates FSKit's attributes from the newest record of the item, for a reply whose record the caller has given to `CacheValidity` itself. Called under the state lock.
	private func populated(_ received: Attributes, _ state: inout State) -> FSItem.Attributes {
		fsItemAttributes(state.ledger.newest(received))
	}

	/// Populates FSKit's attributes for a reply that hands out an item from the newest record of the item, since a newer one may have arrived meanwhile. Gives `CacheValidity` both the received record and that newest one. Called under the state lock.
	private func handedOut(_ item: BridgeItem, _ received: Attributes, _ state: inout State) -> FSItem.Attributes {
		let attributes = state.ledger.newest(received)
		state.validity.handedOut(item, received, newest: attributes)
		return fsItemAttributes(attributes)
	}

	private func fsItemAttributes(_ attributes: Attributes) -> FSItem.Attributes {
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
		let result = try await serve {
			let response = try await client.request(StatfsRequest())
			return try state.withLockUnchecked { state in
				state.statistics.totalBytes = response.totalBytes
				_ = newestUsableBytes(response.freeSpace, &state)
				// handed out without a record, so that CacheValidity does not track it: FSKit refuses every cache state for the root (kIOReturnBadArgument)
				return try required(FSActivateResult(rootItem: state.items.item(for: Messages.rootNodeId)))
			}
		}
		Task.detached {
			await self.watchChanges()
		}
		return result
	}

	func deactivateVolume(options: FSDeactivateOptions = []) async throws {}

	func mount(options: FSTaskOptions) async throws {
		// the mount point exists by now and keeps its name
		name = FSFileName(string: displayName)
	}

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
				return FSLookupItemResult(foundItem: item, itemName: FSFileName(string: response.name), itemAttributes: self.handedOut(item, response.attributes, &state))
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
				let lookups = state.items.removeIfCurrent(item, nodeId: item.nodeId)
				if lookups != nil {
					state.validity.reclaimed(item.nodeId)
				}
				return lookups
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
			let response = try await self.ownEntriesChange(in: request.parentId) {
				try await self.client.request(request)
			} directory: {
				$0.directoryAttributes
			}
			return { state in
				let item = state.items.item(for: response.attributes.nodeId)
				if request.type == .file {
					item.openedByCreate = true
				}
				return FSCreateItemResult(
					newItem: item,
					newItemName: FSFileName(string: response.name),
					newItemAttributes: self.handedOut(item, response.attributes, &state),
					directoryAttributes: self.populated(response.directoryAttributes, &state),
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
			let response = try await self.ownEntriesChange(in: request.parentId) {
				try await self.client.request(request)
			} directory: {
				$0.directoryAttributes
			}
			return { state in
				FSCreateSymlinkResult(
					newItem: state.items.item(for: response.attributes.nodeId),
					newItemName: FSFileName(string: response.name),
					newItemAttributes: self.fsAttributes(response.attributes, &state),
					directoryAttributes: self.populated(response.directoryAttributes, &state),
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
			let request = try RenameRequest(nodeId: nodeId(item), sourceParentId: nodeId(sourceDirectory), sourceName: string(sourceName), destinationParentId: nodeId(destinationDirectory), destinationName: string(destinationName))
			let response = try await ownEntriesChange(in: request.destinationParentId) {
				guard request.sourceParentId != request.destinationParentId else {
					return try await client.request(request)
				}
				return try await ownEntriesChange(in: request.sourceParentId) {
					try await client.request(request)
				} directory: {
					$0.sourceDirectoryAttributes
				}
			} directory: {
				$0.destinationDirectoryAttributes
			}
			return try state.withLockUnchecked { state in
				try required(FSRenameItemResult(
					newName: FSFileName(string: response.name),
					renamedItemAttributes: fsAttributes(response.attributes, &state, .locked),
					sourceDirectoryAttributes: populated(response.sourceDirectoryAttributes, &state),
					destinationDirectoryAttributes: populated(response.destinationDirectoryAttributes, &state),
					overItemAttributes: overItem == nil ? nil : response.replacedAttributes.map { fsAttributes($0, &state, .locked) },
					freeSpace: fsFreeSpace(response.freeSpace, &state)
				))
			}
		}
	}

	func removeItem(_ item: FSItem, named name: FSFileName, from directory: FSItem, context: FSContext) async throws -> FSRemoveItemResult {
		try await serve {
			try authorize(context)
			let request = try RemoveRequest(nodeId: nodeId(item), parentId: nodeId(directory), name: string(name))
			let response = try await ownEntriesChange(in: request.parentId) {
				try await client.request(request)
			} directory: {
				$0.directoryAttributes
			}
			return try state.withLockUnchecked { state in
				try required(FSRemoveItemResult(
					itemAttributes: fsAttributes(response.attributes, &state, .locked),
					directoryAttributes: populated(response.directoryAttributes, &state),
					freeSpace: fsFreeSpace(response.freeSpace, &state)
				))
			}
		}
	}

	func attributes(_ desiredAttributes: FSItem.GetAttributesRequest, of item: FSItem, context: FSContext) async throws -> FSGetAttributesResult {
		try await serve(for: item) {
			try authorize(context)
			let response = try await client.request(GetattrRequest(nodeId: nodeId(item)))
			return try state.withLockUnchecked { state in
				try required(FSGetAttributesResult(attributes: fsAttributes(response.attributes, &state, .locked)))
			}
		}
	}

	func setAttributes(_ newAttributes: FSItem.SetAttributesRequest, on item: FSItem, context: FSContext) async throws -> FSSetAttributesResult {
		try await serve(for: item) {
			try authorize(context)
			let settable: [(FSItem.Attribute, UInt8)] = [(.size, Messages.attributeSize), (.mode, Messages.attributeMode), (.accessTime, Messages.attributeAccessed), (.modifyTime, Messages.attributeModified)]
			let valid = settable.filter { newAttributes.isValid($0.0) }.reduce(0) { $0 | $1.1 }
			let request = try SetattrRequest(
				nodeId: nodeId(item),
				valid: valid,
				size: newAttributes.size,
				mode: UInt16(newAttributes.mode & 0o777),
				accessed: newAttributes.isValid(.accessTime) ? timestamp(newAttributes.accessTime) : Timestamp(seconds: 0, nanos: 0),
				modified: newAttributes.isValid(.modifyTime) ? timestamp(newAttributes.modifyTime) : Timestamp(seconds: 0, nanos: 0)
			)
			let response = try await ownChange(of: request.nodeId, setting: valid) {
				try await client.request(request)
			} applied: { response in
				// a reply whose size differs from the one requested shows a change outside: the server resized a channel whose file was replaced meanwhile. That size is judged rather than adopted.
				let adopted = response.attributes.size == request.size ? response.applied : response.applied & ~Messages.attributeSize
				return .setattr(applied: adopted, response.attributes)
			}
			// the server leaves out a size a file already had, which took effect all the same
			let tookEffect = response.applied | (valid & Messages.attributeSize != 0 && response.attributes.type == .file && response.attributes.size == request.size ? Messages.attributeSize : 0)
			for (attribute, bit) in settable where tookEffect & bit != 0 {
				newAttributes.consumedAttributes.insert(attribute)
			}
			return try state.withLockUnchecked { state in
				try required(FSSetAttributesResult(attributes: populated(response.attributes, &state), freeSpace: fsFreeSpace(response.freeSpace, &state)))
			}
		}
	}

	/// Sends a request that changes an item or the names in a directory, and tells `CacheValidity` what it applied, so that the change is not taken for one made outside the volume.
	///
	/// - Parameter fields: The fields the request may set, as bits of `Messages`.
	private func ownChange<Response>(of nodeId: UInt64, setting fields: UInt8, _ send: () async throws -> Response, applied: (Response) -> CacheValidity<BridgeItem>.OwnChange) async throws -> Response {
		state.withLockUnchecked { $0.validity.ownChangeBegan(nodeId, setting: fields) }
		do {
			let response = try await send()
			state.withLockUnchecked { $0.validity.ownChangeEnded(nodeId, applied(response)) }
			return response
		} catch {
			state.withLockUnchecked { $0.validity.ownChangeEnded(nodeId, nil) }
			throw error
		}
	}

	/// Sends a request that creates, links, removes or renames an entry in a directory, as one of the volume's own changes to that directory's size and modification time.
	///
	/// - Parameter directory: The directory's record in the reply.
	private func ownEntriesChange<Response>(in nodeId: UInt64, _ send: () async throws -> Response, directory: (Response) -> Attributes) async throws -> Response {
		try await ownChange(of: nodeId, setting: Messages.attributeSize | Messages.attributeModified, send) { .entries(directory($0)) }
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
		try await serve(for: item) {
			try authorize(context)
			let response = try await client.request(ReadlinkRequest(nodeId: nodeId(item)))
			return try state.withLockUnchecked { state in
				try required(FSReadSymlinkResult(contents: FSFileName(string: response.target), symlinkAttributes: fsAttributes(response.attributes, &state, .locked)))
			}
		}
	}
}

/// Opens and closes items, and grants the kernel caching.
///
/// FSKit closes an item once its last descriptor is closed. An open that finds its file changed outside the volume revokes it and fails with `ESTALE`: an open for reading would be served from pages of the old version, or with new bytes cut to the old size where none were cached, and one for writing could put stale pages back over the new version.
extension BridgeVolume: FSVolume.DataCacheHandler {
	private func modes(_ modes: FSVolume.OpenModes) -> UInt8 {
		(modes.contains(.read) ? Messages.modeRead : 0) | (modes.contains(.write) ? Messages.modeWrite : 0)
	}

	/// The coherency granted for a cache mode.
	private func coherency(_ cacheMode: FSVolume.DataCacheMode) -> FSVolume.KernelCacheCoherencyType {
		switch cacheMode {
		case .readWithCache: .readCache
		// writers cache as well: a page written before a change outside in place goes back over it, which a revoke does not prevent, but a change that replaces the file stays intact, on a vault as well. Without the cache every write waits for its reply, which makes a large write more than twice as slow.
		case .readWriteWithCache: .writeBack
		default: .noCache
		}
	}

	/// Serves an open or an upgrade, which grants the kernel a cache of an item. The grant is replied under the state lock, in the section that finds the item current, which only the reply-handler form allows. A revoke that section finds due becomes the caller's: it runs the revoke outside the lock and refuses with `ESTALE`, as it does while a revoke of the item runs or after one ran.
	///
	/// - Parameters:
	///   - prepare: Runs at once: checks the caller and returns the item, since the handler's arguments cannot go to the task that serves it.
	///   - send: Sends what the grant needs, and runs only for an item that is current. If the item is no longer current once it returned, the server's channel it may have opened is closed, unless an overlapping open was granted.
	private func serveGrant<Result>(_ reply: @escaping @Sendable (Result?, (any Error)?) -> Void, prepare: () throws -> BridgeItem, send: (@Sendable (BridgeItem) async throws -> Void)? = nil, granted: @escaping @Sendable () -> Result) {
		let ticket = begin()
		let item: BridgeItem
		do {
			item = try prepare()
		} catch {
			reply(nil, fsError(error))
			finish(ticket)
			return
		}
		Task {
			defer {
				self.finish(ticket)
				self.dropDueCaches()
			}
			let stale = fs_errorForPOSIXError(POSIXError.ESTALE.rawValue)
			do {
				if let send {
					// FSKit passes on later opens of a revoked item that a process holds open: refuse them before an OPEN can touch the channel that process reads through
					guard self.admit(item) == .grant else {
						throw stale
					}
					try await send(item)
				}
				let admission = self.admit(item) {
					item.isOpen = true
					reply(granted(), nil)
				}
				guard admission == .grant else {
					// first opens of an item overlap, and one that was granted holds the same channel
					if send != nil, !self.state.withLockUnchecked({ _ in item.isOpen }) {
						try? await self.closeChannel(of: item)
					}
					throw stale
				}
			} catch {
				reply(nil, self.failure(error, for: item))
			}
		}
	}

	/// Decides whether the kernel may cache the item, and runs the revoke that became the caller's.
	///
	/// - Parameter grant: Replies with the grant. It runs under the state lock if the kernel may cache the item.
	private func admit(_ item: BridgeItem, grant: () -> Void = {}) -> CacheValidity<BridgeItem>.Admission {
		let admission = state.withLockUnchecked { state in
			let admission = state.validity.admit(item, nodeId: item.nodeId)
			if admission == .grant {
				grant()
			}
			return admission
		}
		if admission == .revoke {
			revoke(item)
		}
		return admission
	}

	/// Closes the server's channel of an item. Clears `openedByCreate` and `isOpen` before the `CLOSE` is sent, so that an open from then on is forwarded and reopens the channel.
	private func closeChannel(of item: BridgeItem) async throws {
		state.withLockUnchecked { _ in
			item.openedByCreate = false
			item.isOpen = false
		}
		try await record(client.request(CloseRequest(nodeId: item.nodeId, keptModes: 0)).freeSpace)
	}

	func open(_ item: FSItem, modes: FSVolume.OpenModes, cacheMode: FSVolume.DataCacheMode, context: FSContext, replyHandler reply: @escaping @Sendable (FSOpenItemResult?, (any Error)?) -> Void) {
		let requested = self.modes(modes)
		let granting = coherency(cacheMode)
		serveGrant(reply) {
			try authorize(context)
			return try bridgeItem(item)
		} send: { item in
			// the server opened a file created through the volume for reading and writing
			if !self.state.withLockUnchecked({ _ in item.openedByCreate }) {
				let response = try await self.client.request(OpenRequest(nodeId: item.nodeId, modes: requested))
				self.state.withLockUnchecked { $0.validity.compare(response.attributes, .locked) }
			}
		} granted: {
			FSOpenItemResult(grantedCoherency: granting)
		}
	}

	func close(_ item: FSItem, context: FSContext) async {
		// no caller is refused here: a close that does not reach the server leaves its channel open
		guard let item = item as? BridgeItem else {
			return
		}
		_ = try? await serve {
			try await closeChannel(of: item)
		}
	}

	func upgrade(_ item: FSItem, cacheMode: FSVolume.DataCacheMode, context: FSContext, replyHandler reply: @escaping @Sendable (FSUpgradeItemResult?, (any Error)?) -> Void) {
		let granting = coherency(cacheMode)
		// observed only for an open for reading of a revoked item that a process holds open, which is refused. FSKit's header describes an open that asks an open item for more caching, whose channel is open already, so nothing is sent.
		serveGrant(reply) {
			try authorize(context)
			return try bridgeItem(item)
		} granted: {
			FSUpgradeItemResult(grantedCoherency: granting)
		}
	}
}

extension BridgeVolume: FSVolume.ReadWriteHandler {
	func read(from item: FSItem, at offset: off_t, length: Int, into buffer: FSMutableFileDataBuffer) async throws -> FSReadFileResult {
		try await serve(for: item) {
			let response = try await client.read(nodeId: nodeId(item), offset: UInt64(offset), length: min(length, buffer.length))
			_ = buffer.withUnsafeMutableBytes { destination in
				response.data.copyBytes(to: destination)
			}
			return try state.withLockUnchecked { state in
				try required(FSReadFileResult(bytesRead: response.data.count, itemAttributes: fsAttributes(response.attributes, &state, .locked)))
			}
		}
	}

	func write(contents: Data, to item: FSItem, at offset: off_t) async throws -> FSWriteFileResult {
		try await serve(for: item) {
			let nodeId = try nodeId(item)
			let response = try await ownChange(of: nodeId, setting: Messages.attributeSize | Messages.attributeModified) {
				try await client.write(nodeId: nodeId, offset: UInt64(offset), data: contents)
			} applied: { response in
				.write(end: UInt64(offset) + UInt64(response.written), response.attributes)
			}
			return try state.withLockUnchecked { state in
				try required(FSWriteFileResult(bytesWritten: Int(response.written), itemAttributes: populated(response.attributes, &state), freeSpace: fsFreeSpace(response.freeSpace, &state)))
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
