import FSKit
import Foundation

@main
struct FSKitNioExtension: UnaryFileSystemExtension {

	let fileSystem = BridgeFileSystem()
}
