import Foundation
import Testing
@testable import FSKitNioSupport

struct EmbeddingTests {
	private struct Appex {
		var bundleIdentifier: String
		var extensionPoint = Embedding.extensionPoint
		var shortName: String
	}

	/// Builds a bundle with the given name and extensions in a new temporary directory, and checks it for `cryptomatorfs`.
	private func embedding(bundleName: String = "Host.app", _ extensions: [Appex]?) throws -> Embedding {
		let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
		defer { try? FileManager.default.removeItem(at: directory) }
		let bundle = directory.appending(component: bundleName, directoryHint: .isDirectory)
		try FileManager.default.createDirectory(at: bundle.appending(components: "Contents", "MacOS"), withIntermediateDirectories: true)
		for appex in extensions ?? [] {
			let contents = bundle.appending(components: "Contents", "Extensions", "\(appex.bundleIdentifier).appex", "Contents", directoryHint: .isDirectory)
			try FileManager.default.createDirectory(at: contents, withIntermediateDirectories: true)
			let info: [String: Any] = [
				"CFBundleIdentifier": appex.bundleIdentifier,
				"EXAppExtensionAttributes": ["EXExtensionPointIdentifier": appex.extensionPoint, "FSShortName": appex.shortName]
			]
			try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0).write(to: contents.appending(component: "Info.plist"))
		}
		return Embedding(of: "cryptomatorfs", in: bundle)
	}

	@Test func aBundleThatIsNoAppIsNotChecked() throws {
		#expect(try embedding(bundleName: "Host", [Appex(bundleIdentifier: "org.example.fs", shortName: "cryptomatorfs")]) == .notInApp)
	}

	@Test func anAppWithoutExtensionsEmbedsNone() throws {
		#expect(try embedding(nil) == .notEmbedded)
	}

	@Test func anExtensionOfAnotherExtensionPointDoesNotCount() throws {
		#expect(try embedding([Appex(bundleIdentifier: "org.example.other", extensionPoint: "com.apple.share-services", shortName: "cryptomatorfs")]) == .notEmbedded)
	}

	@Test func anExtensionForAnotherTypeDoesNotCount() throws {
		#expect(try embedding([Appex(bundleIdentifier: "org.example.fs", shortName: "otherfs")]) == .notEmbedded)
	}

	@Test func findsTheExtensionForTheTypeAmongOthers() throws {
		let extensions = [
			Appex(bundleIdentifier: "org.example.other", extensionPoint: "com.apple.share-services", shortName: "cryptomatorfs"),
			Appex(bundleIdentifier: "org.example.otherfs", shortName: "otherfs"),
			Appex(bundleIdentifier: "org.example.fs", shortName: "cryptomatorfs")
		]

		#expect(try embedding(extensions) == .embedded(bundleIdentifier: "org.example.fs"))
	}
}

struct ExtensionStatusTests {
	@Test(arguments: [
		(Embedding.notInApp, ExtensionStatus.notInApp),
		(.notEmbedded, .notEmbedded),
		(.embedded(bundleIdentifier: "org.example.fs"), .embedded)
	])
	func anEmbeddingAloneDecidesWithoutFSKit(embedding: Embedding, expected: ExtensionStatus) {
		#expect(ExtensionStatus(embedding) == expected)
	}

	@Test(arguments: [
		([InstalledExtension(bundleIdentifier: "org.example.other", isEnabled: true)], ExtensionStatus.notRegistered),
		([], .notRegistered),
		([InstalledExtension(bundleIdentifier: "org.example.other", isEnabled: true), InstalledExtension(bundleIdentifier: "org.example.fs", isEnabled: false)], .disabled),
		([InstalledExtension(bundleIdentifier: "org.example.fs", isEnabled: true)], .enabled)
	])
	func fskitTellsWhetherTheEmbeddedExtensionIsRegisteredAndEnabled(installed: [InstalledExtension], expected: ExtensionStatus) {
		#expect(ExtensionStatus(bundleIdentifier: "org.example.fs", installed: installed) == expected)
	}

	/// The codes the Java test of `NativeExtensionCheck` decodes.
	@Test(arguments: [
		(ExtensionStatus.notInApp, Int32(0)),
		(.notEmbedded, 1),
		(.embedded, 2),
		(.notRegistered, 3),
		(.disabled, 4),
		(.enabled, 5),
		(.fskitFailed, -1),
		(.timedOut, -2)
	])
	func eachStatusHasTheCodeTheJavaProviderDecodes(status: ExtensionStatus, code: Int32) {
		#expect(status.rawValue == code)
	}
}
