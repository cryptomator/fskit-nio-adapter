package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptofs.DirStructure;
import org.cryptomator.cryptolib.common.MasterkeyFileAccess;
import org.cryptomator.integrations.mount.Mount;
import org.cryptomator.integrations.mount.MountFailedException;
import org.cryptomator.integrations.mount.MountService;
import org.cryptomator.integrations.mount.UnmountFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Test programs to mirror existing directories or a vault.
 * <p>
 * The FSKit extension must be installed and enabled, see the README.
 */
public class MirroringFSKitMountTest {

	private static final Logger LOG;

	static {
		// takes effect only if set before the first logger is created
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
		System.setProperty("org.slf4j.simpleLogger.log.org.cryptomator.frontend.fskit.BridgeSession", "trace");
		System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
		System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
		LOG = LoggerFactory.getLogger(MirroringFSKitMountTest.class);
	}

	/**
	 * Mirror one or more directories
	 */
	public static class Mirror {

		public static void main(String[] args) throws MountFailedException {
			var mountService = findMountService();
			try (Scanner scanner = new Scanner(System.in)) {
				List<Mirrored> mirrored = new ArrayList<>();
				while (true) {
					LOG.info(mirrored.isEmpty() ? "Enter path to the directory you want to mirror:" : "Enter path to another directory you want to mirror, or nothing to continue:");
					String pathToMirror = scanner.nextLine();
					if (pathToMirror.isBlank()) {
						break;
					}
					LOG.info("Enter mount point:");
					mirrored.add(new Mirrored(Path.of(pathToMirror), Path.of(scanner.nextLine()), mirrored.isEmpty() ? "Mirror" : "Mirror " + (mirrored.size() + 1)));
				}
				mount(mountService, mirrored, scanner);
			}
		}

	}

	/**
	 * Mirror vault
	 */
	public static class CryptoFsMirror {

		public static void main(String[] args) throws IOException, NoSuchAlgorithmException, MountFailedException {
			var mountService = findMountService();
			try (Scanner scanner = new Scanner(System.in)) {
				LOG.info("Enter path to the vault you want to mirror:");
				Path vaultPath = Path.of(scanner.nextLine());
				if (CryptoFileSystemProvider.checkDirStructureForVault(vaultPath, "vault.cryptomator", "masterkey.cryptomator") != DirStructure.VAULT) {
					throw new IllegalArgumentException("Not a vault: " + vaultPath);
				}

				LOG.info("Enter vault password:");
				String passphrase = scanner.nextLine();

				LOG.info("Enter mount point:");
				Path mountPoint = Path.of(scanner.nextLine());

				SecureRandom csprng = SecureRandom.getInstanceStrong();
				CryptoFileSystemProperties props = CryptoFileSystemProperties.cryptoFileSystemProperties()
						.withKeyLoader(url -> new MasterkeyFileAccess(new byte[0], csprng).load(vaultPath.resolve("masterkey.cryptomator"), passphrase))
						.build();
				try (FileSystem cryptoFs = CryptoFileSystemProvider.newFileSystem(vaultPath, props)) {
					mount(mountService, List.of(new Mirrored(cryptoFs.getPath("/"), mountPoint, "Mirror")), scanner);
				}
			}
		}

	}

	private static MountService findMountService() throws MountFailedException {
		var mountService = MountService.get().findAny().orElseThrow(() -> new MountFailedException("Did not find a mount provider"));
		LOG.info("Using mount provider: {}", mountService.displayName());
		return mountService;
	}

	private record Mirrored(Path pathToMirror, Path mountPoint, String volumeName) {
	}

	private static void mount(MountService mountProvider, List<Mirrored> mirrored, Scanner scanner) throws MountFailedException {
		LOG.info("Mount read-only? (y/N)");
		boolean readOnly = scanner.nextLine().strip().equalsIgnoreCase("y");

		List<Mount> mounts = new ArrayList<>();
		try {
			for (Mirrored m : mirrored) {
				var mount = mountProvider.forFileSystem(m.pathToMirror()).setVolumeName(m.volumeName()).setReadOnly(readOnly).setMountpoint(m.mountPoint()).mount();
				mounts.add(mount);
				LOG.info("Mounted successfully to: {}", mount.getMountpoint().uri());
			}
			LOG.info("Enter anything to unmount...");
			scanner.nextLine();
		} finally {
			mounts.forEach(MirroringFSKitMountTest::unmount);
		}
	}

	static void unmount(Mount mount) {
		try (mount) {
			try {
				mount.unmount();
			} catch (UnmountFailedException e) {
				LOG.warn("Graceful unmount failed. Attempting force-unmount...");
				mount.unmountForced();
			}
		} catch (UnmountFailedException e) {
			LOG.warn("Unmount failed.", e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
