package org.cryptomator.frontend.fskit;

import org.cryptomator.frontend.fskit.fs.FileSystemOperations;
import org.cryptomator.frontend.fskit.protocol.Manifest;
import org.cryptomator.frontend.fskit.protocol.Messages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Serves an empty temporary directory to the Swift client without mounting anything, see {@code fskit/scripts/interop-test.sh}.
 * <p>
 * Prints the directory holding the manifest and serves until it reads anything from standard input.
 */
public class BridgeServerMain {

	static final String RENDEZVOUS_DIR_PREFIX = "RENDEZVOUS_DIR ";

	public static void main(String[] args) throws IOException {
		Path root = Files.createTempDirectory("fskit-interop-root");
		Path rendezvousDir = Files.createTempDirectory("fskit-interop-rendezvous");
		try (BridgeSession session = new BridgeSession(new FileSystemOperations(root, false), Duration.ofSeconds(10))) {
			new Manifest(Messages.PROTOCOL_VERSION, session.port(), session.token(), "Interop ä").write(rendezvousDir);
			IO.println(RENDEZVOUS_DIR_PREFIX + rendezvousDir);
			System.in.read();
		} finally {
			deleteRecursively(root);
			deleteRecursively(rendezvousDir);
		}
	}

	private static void deleteRecursively(Path directory) throws IOException {
		try (Stream<Path> paths = Files.walk(directory)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}
}
