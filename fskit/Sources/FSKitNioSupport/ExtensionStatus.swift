/// An extension as FSKit lists it.
public struct InstalledExtension: Equatable, Sendable {
	public var bundleIdentifier: String
	public var isEnabled: Bool

	public init(bundleIdentifier: String, isEnabled: Bool) {
		self.bundleIdentifier = bundleIdentifier
		self.isEnabled = isEnabled
	}
}

/// The answers of the exported functions. `NativeExtensionCheck` in the Java provider mirrors the codes.
public enum ExtensionStatus: Int32, Sendable {
	case notInApp = 0
	case notEmbedded = 1
	/// The app embeds the extension. Only the check that does not ask FSKit answers this.
	case embedded = 2
	/// The app embeds the extension, and FSKit does not list it.
	case notRegistered = 3
	case disabled = 4
	case enabled = 5
	case fskitFailed = -1
	case timedOut = -2

	public init(_ embedding: Embedding) {
		switch embedding {
		case .notInApp:
			self = .notInApp
		case .notEmbedded:
			self = .notEmbedded
		case .embedded:
			self = .embedded
		}
	}

	/// - Parameters:
	///   - bundleIdentifier: The identifier of the extension the app embeds.
	///   - installed: The extensions FSKit lists to the caller.
	public init(bundleIdentifier: String, installed: [InstalledExtension]) {
		guard let registered = installed.first(where: { $0.bundleIdentifier == bundleIdentifier }) else {
			self = .notRegistered
			return
		}
		self = registered.isEnabled ? .enabled : .disabled
	}
}
