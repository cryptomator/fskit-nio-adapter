package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.api.MasterkeyLoader;
import org.cryptomator.integrations.mount.Mount;
import org.cryptomator.integrations.mount.MountFailedException;
import org.cryptomator.integrations.mount.UnmountFailedException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mounts and unmounts directories and throwaway vaults on command, see {@code fskit/scripts/smoke-test.sh}.
 * <p>
 * Reads one command per line from standard input, its fields separated by tabs, and answers each with one log line whose message starts with {@code REPLY}. The replies are logged, so that they share one ordered stream with the log lines of the sessions.
 */
public class SmokeMountMain {

	private static final Logger LOG;

	static {
		// takes effect only if set before the first logger is created. The script matches lines of this format and needs the debug lines of BridgeSession.
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
		System.setProperty("org.slf4j.simpleLogger.log.org.cryptomator.frontend.fskit.BridgeSession", "trace");
		System.setProperty("org.slf4j.simpleLogger.showDateTime", "true");
		System.setProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss.SSS");
		LOG = LoggerFactory.getLogger(SmokeMountMain.class);
	}

	private final FSKitMountProvider provider = new FSKitMountProvider();
	private final Map<String, Mounted> mounted = new LinkedHashMap<>();

	/**
	 * @param vault The vault whose root is mounted, or {@code null} if a plain directory is
	 */
	private record Mounted(Mount mount, @Nullable FileSystem vault) {
	}

	public static void main(String[] args) throws IOException {
		new SmokeMountMain().run();
	}

	private void run() throws IOException {
		try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			reply("READY", "-");
			String command;
			while ((command = commands.readLine()) != null && !command.equals("quit")) {
				handle(command);
			}
		} finally {
			for (Mounted volume : mounted.values()) {
				MirroringFSKitMountTest.unmount(volume.mount());
				if (volume.vault() != null) {
					volume.vault().close();
				}
			}
		}
		reply("BYE", "-");
	}

	private void handle(String command) {
		String[] fields = command.split("\t", -1);
		String id = fields.length > 1 ? fields[1] : "-";
		if (fields[0].equals("mount") && fields.length == 6 && Set.of("plain", "vault").contains(fields[2]) && Set.of("rw", "ro").contains(fields[3]) && !mounted.containsKey(id)) {
			mount(id, fields[2].equals("vault"), fields[3].equals("ro"), fields[4], fields[5]);
		} else if (fields[0].equals("unmount") && fields.length == 2 && mounted.containsKey(id)) {
			unmount(id, false);
		} else if (fields[0].equals("unmount-forced") && fields.length == 2 && mounted.containsKey(id)) {
			unmount(id, true);
		} else if (fields[0].equals("sync") && fields.length == 2) {
			reply("SYNCED", id);
		} else {
			reply("ERROR", id, "Unable to carry out: " + command);
		}
	}

	private void mount(String id, boolean isVault, boolean readOnly, String backingDir, String mountPoint) {
		try {
			FileSystem vault = isVault ? openVault(Path.of(backingDir)) : null;
			try {
				Path root = vault != null ? vault.getPath("/") : Path.of(backingDir);
				Mount mount = provider.forFileSystem(root).setVolumeName("Smoke " + id).setReadOnly(readOnly).setMountpoint(Path.of(mountPoint)).mount();
				mounted.put(id, new Mounted(mount, vault));
			} catch (MountFailedException | RuntimeException e) {
				if (vault != null) {
					vault.close();
				}
				throw e;
			}
			reply("MOUNTED", id);
		} catch (MountFailedException | IOException | RuntimeException e) {
			reply("MOUNT_FAILED", id, e.toString());
		}
	}

	/**
	 * Opens the vault in a directory, which it creates there first if the directory holds none.
	 */
	private static FileSystem openVault(Path directory) throws IOException {
		MasterkeyLoader keyLoader = _ -> new Masterkey(new byte[64]);
		CryptoFileSystemProperties properties = CryptoFileSystemProperties.cryptoFileSystemProperties().withKeyLoader(keyLoader).build();
		if (!Files.exists(directory.resolve("vault.cryptomator"))) {
			CryptoFileSystemProvider.initialize(directory, properties, URI.create("test:key"));
		}
		return CryptoFileSystemProvider.newFileSystem(directory, properties);
	}

	/**
	 * A volume whose unmount failed stays known, so that it can be unmounted again.
	 */
	private void unmount(String id, boolean forced) {
		Mounted volume = mounted.get(id);
		try {
			if (forced) {
				volume.mount().unmountForced();
			} else {
				volume.mount().unmount();
			}
			if (volume.vault() != null) {
				volume.vault().close();
			}
		} catch (UnmountFailedException | IOException e) {
			reply("UNMOUNT_FAILED", id, e.toString());
			return;
		}
		mounted.remove(id);
		reply("UNMOUNTED", id);
	}

	private static void reply(String marker, String id) {
		LOG.info("REPLY {} {}", marker, id);
	}

	private static void reply(String marker, String id, String message) {
		// a reply is one line
		LOG.info("REPLY {} {} {}", marker, id, message.replaceAll("[\\t\\r\\n]", " "));
	}
}
