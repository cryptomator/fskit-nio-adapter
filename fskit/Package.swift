// swift-tools-version: 6.2
import PackageDescription

let package = Package(
	name: "FSKitNio",
	platforms: [
		.macOS("27.0")
	],
	targets: [
		.target(
			name: "FSKitBridge"
		),
		.executableTarget(
			name: "FSKitNioExtension",
			dependencies: [
				"FSKitBridge"
			],
			swiftSettings: [
				.swiftLanguageMode(.v5)
			],
			linkerSettings: [
				// ExtensionKit extensions must enter through NSExtensionMain (Xcode
				// links appex targets with `-e _NSExtensionMain`). With the default
				// Swift entry point, ExtensionFoundation fails with "Unrecognized
				// extension type" and the extension exits before serving XPC.
				.unsafeFlags(["-Xlinker", "-e", "-Xlinker", "_NSExtensionMain"])
			]
		),
		.executableTarget(
			name: "FSKitNioHost"
		),
		.testTarget(
			name: "FSKitBridgeTests",
			dependencies: [
				"FSKitBridge"
			]
		)
	]
)
