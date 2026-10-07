/// Lists directories through a client for FSKit's enumerations.
///
/// FSKit's packer takes fewer entries than a `READDIR` page holds. When it refuses an entry, the lister keeps the entries of that page from the first it offered on. A later call that continues at one of them is served from what was kept, as long as no other request was sent in between, which might have changed the listing.
///
/// An enumeration without attributes continues at an entry the packer already took, not at the one it refused, so any kept entry can be where a call continues.
///
/// Confined to the volume's queue, like the client.
public final class DirectoryLister {
	/// Entries of a page, and what a call has to match to continue with them.
	private struct Rest {
		var nodeId: UInt64
		var verifier: UInt64
		var wantAttributes: Bool
		/// The cookie of the first entry.
		var cookie: UInt64
		var entries: ArraySlice<DirectoryEntry>
		var more: Bool
		var nextRequestId: UInt64

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
	private var rest: Rest?

	public init(client: BridgeClient) {
		self.client = client
	}

	/// Hands the entries of a directory from `cookie` on to `pack` until it refuses one or the listing ends.
	///
	/// - Parameter pack: Returns whether it took the entry.
	/// - Returns: The verifier of the listing.
	public func list(directory nodeId: UInt64, cookie: UInt64, verifier: UInt64, wantAttributes: Bool, pack: (DirectoryEntry) -> Bool) throws -> UInt64 {
		var cookie = cookie
		var verifier = verifier
		var kept: (entries: ArraySlice<DirectoryEntry>, more: Bool)?
		// cookie 0 starts a new listing
		if let rest, cookie != 0, rest.nodeId == nodeId, rest.verifier == verifier, rest.wantAttributes == wantAttributes, rest.nextRequestId == client.nextRequestId, let entries = rest.entries(from: cookie) {
			kept = (entries, rest.more)
		}
		rest = nil
		while true {
			let first = cookie
			let entries: ArraySlice<DirectoryEntry>
			let more: Bool
			if let page = kept {
				entries = page.entries
				more = page.more
				kept = nil
			} else {
				let page = try client.request(ReaddirRequest(nodeId: nodeId, cookie: cookie, verifier: verifier, wantAttributes: wantAttributes))
				verifier = page.verifier
				entries = page.entries[...]
				more = page.more
			}
			for index in entries.indices {
				guard pack(entries[index]) else {
					rest = Rest(nodeId: nodeId, verifier: verifier, wantAttributes: wantAttributes, cookie: first, entries: entries, more: more, nextRequestId: client.nextRequestId)
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
