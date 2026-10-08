// swift-tools-version: 6.2
import PackageDescription

let package = Package(
	name: "FSKitNio",
	platforms: [
		.macOS("27.0")
	],
	products: [
		// loaded by the Java provider through FFM, to check the app it runs in for the extension
		.library(name: "FSKitNioSupport", type: .dynamic, targets: ["FSKitNioSupport"])
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
		.target(
			name: "FSKitNioSupport"
		),
		.testTarget(
			name: "FSKitBridgeTests",
			dependencies: [
				"FSKitBridge"
			]
		),
		.testTarget(
			name: "FSKitNioSupportTests",
			dependencies: [
				"FSKitNioSupport"
			]
		)
	]
)
