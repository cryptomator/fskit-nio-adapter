package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.integrations.mount.MountFailedException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.cryptomator.frontend.fskit.mount.NativeLibrary.invoke;

/**
 * Mounts through {@link NativeLibrary}, which calls {@code FSClient.mountSingleVolume}. FSKit mounts this way only for an app with the entitlement {@value ENTITLEMENT}.
 */
class NativeVolumeMounter implements VolumeMounter {

	static final NativeVolumeMounter SHARED = new NativeVolumeMounter(NativeLibrary.SHARED, FSKitMountProvider.MOUNT_TIMEOUT_SECONDS * 1000);
	private static final String ENTITLEMENT = "com.apple.developer.fskit.mount";
	private static final int BUFFER_SIZE = 4096;

	// the codes of MountStatus in the library
	private static final int MOUNTED = 0;
	private static final int NOT_ENTITLED = 1;
	private static final int FAILED = -1;
	private static final int TIMED_OUT = -2;

	private final NativeLibrary library;
	private final int timeoutMillis;

	NativeVolumeMounter(NativeLibrary library, int timeoutMillis) {
		this.library = library;
		this.timeoutMillis = timeoutMillis;
	}

	@Override
	public Path mount(String fsType, Path rendezvousDir, boolean readOnly) throws MountFailedException {
		NativeLibrary.Functions functions = library.functions();
		if (functions == null) {
			throw new MountFailedException("Failed to load " + library.name() + ", which mounts without a mount point");
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment buffer = arena.allocate(BUFFER_SIZE);
			int code = invoke(functions.mount(), arena.allocateFrom(fsType), arena.allocateFrom(rendezvousDir.toString()), readOnly ? 1 : 0, timeoutMillis, buffer, BUFFER_SIZE);
			return decode(code, buffer.getString(0, StandardCharsets.UTF_8));
		}
	}

	/**
	 * @param text What the library wrote into the buffer
	 */
	Path decode(int code, String text) throws MountFailedException {
		return switch (code) {
			case MOUNTED -> Path.of(text);
			case NOT_ENTITLED -> throw new MountFailedException("The app lacks the entitlement " + ENTITLEMENT + ", which it needs to mount without a mount point");
			case FAILED -> throw new MountFailedException("Failed to mount through FSKit: " + text);
			case TIMED_OUT -> throw new MountFailedException("FSKit did not mount within " + timeoutMillis + " ms. A volume it mounts later is unmounted then.");
			default -> throw new MountFailedException(library.name() + " answered the unknown code " + code);
		};
	}
}
