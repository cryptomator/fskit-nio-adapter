import os

/// Lists directories through a client for FSKit's enumerations.
///
/// FSKit's packer takes fewer entries than a `READDIR` page holds. When it refuses an entry, the lister keeps the entries of that page from the first it offered on. A later call that continues at one of them is served from what was kept, as long as nothing could have changed the listing meanwhile. The page is kept only if no other request was in flight when its `READDIR` was sent and none was sent until its response arrived. It is used only if no request was sent since, so none can be in flight either.
///
/// An enumeration without attributes continues at an entry the packer already took, not at the one it refused, so any kept entry can be where a call continues.
public final class DirectoryLister: Sendable {
	/// Entries of a page, and what a call has to match to continue with them.
	private struct Rest: Sendable {
		var nodeId: UInt64
		var verifier: UInt64
		var wantAttributes: Bool
		/// The cookie of the first entry.
		var cookie: UInt64
		var entries: ArraySlice<DirectoryEntry>
		var more: Bool
		/// The number of requests the client had sent when the page arrived.
		var sent: UInt64

		/// The entries from the one at `cookie` on, if that is one of them.
		func entries(from cookie: UInt64) -> ArraySlice<DirectoryEntry>? {
			if cookie == self.cookie {
				return entries
			}
			guard let previous = entries.firstIndex(where: { $0.nextCookie == cookie }), previous + 1 < entries.endIndex else {
				return nil
			}
			return entries[(previous + 1)...]
		}
	}

	private let client: BridgeClient
	private let rest = OSAllocatedUnfairLock<Rest?>(initialState: nil)

	public init(client: BridgeClient) {
		self.client = client
	}

	/// Hands the entries of a directory from `cookie` on to `pack` until it refuses one or the listing ends.
	///
	/// - Parameter pack: Returns whether it took the entry.
	/// - Returns: The verifier of the listing.
	public func list(directory nodeId: UInt64, cookie: UInt64, verifier: UInt64, wantAttributes: Bool, pack: (DirectoryEntry) -> Bool) async throws -> UInt64 {
		var cookie = cookie
		var verifier = verifier
		var kept = rest.withLock { rest in
			defer { rest = nil }
			return rest
		}
		// cookie 0 starts a new listing
		if let taken = kept, cookie != 0, taken.nodeId == nodeId, taken.verifier == verifier, taken.wantAttributes == wantAttributes, let entries = taken.entries(from: cookie), client.requestCounts.sent == taken.sent {
			kept?.entries = entries
		} else {
			kept = nil
		}
		while true {
			let first = cookie
			let entries: ArraySlice<DirectoryEntry>
			let more: Bool
			// the number of requests sent when the page arrived, if nothing that could have changed the listing overlapped it
			let sent: UInt64?
			if let page = kept {
				entries = page.entries
				more = page.more
				sent = page.sent
				kept = nil
			} else {
				let before = client.requestCounts
				let page = try await client.request(ReaddirRequest(nodeId: nodeId, cookie: cookie, verifier: verifier, wantAttributes: wantAttributes))
				let after = client.requestCounts
				verifier = page.verifier
				entries = page.entries[...]
				more = page.more
				sent = before.isQuiet && after.sent == before.sent + 1 ? after.sent : nil
			}
			for index in entries.indices {
				guard pack(entries[index]) else {
					if let sent {
						let refused = Rest(nodeId: nodeId, verifier: verifier, wantAttributes: wantAttributes, cookie: first, entries: entries, more: more, sent: sent)
						rest.withLock { $0 = refused }
					}
					return verifier
				}
				cookie = entries[index].nextCookie
			}
			guard more, !entries.isEmpty else {
				return verifier
			}
		}
	}
}
