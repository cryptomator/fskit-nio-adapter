import Foundation

/// What an app bundle holds for a file system type.
public enum Embedding: Equatable, Sendable {
	/// The bundle is no `.app`.
	case notInApp
	case notEmbedded
	case embedded(bundleIdentifier: String)

	static let extensionPoint = "com.apple.fskit.fsmodule"

	/// Looks for the FSKit extension whose `FSShortName` is the file system type, among the extensions in the app's `Contents/Extensions`.
	public init(of shortName: String, in bundleURL: URL) {
		guard bundleURL.pathExtension == "app" else {
			self = .notInApp
			return
		}
		let extensions = bundleURL.appending(components: "Contents", "Extensions", directoryHint: .isDirectory)
		let appexes = (try? FileManager.default.contentsOfDirectory(at: extensions, includingPropertiesForKeys: nil)) ?? []
		for appex in appexes where appex.pathExtension == "appex" {
			guard let data = try? Data(contentsOf: appex.appending(components: "Contents", "Info.plist")),
			      let info = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
			      let attributes = info["EXAppExtensionAttributes"] as? [String: Any],
			      attributes["EXExtensionPointIdentifier"] as? String == Self.extensionPoint,
			      attributes["FSShortName"] as? String == shortName,
			      let bundleIdentifier = info["CFBundleIdentifier"] as? String
			else {
				continue
			}
			self = .embedded(bundleIdentifier: bundleIdentifier)
			return
		}
		self = .notEmbedded
	}
}
