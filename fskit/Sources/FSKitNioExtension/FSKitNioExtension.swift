import Foundation
import FSKit

@main
struct FSKitNioExtension: UnaryFileSystemExtension {
	let fileSystem = BridgeFileSystem()
}
