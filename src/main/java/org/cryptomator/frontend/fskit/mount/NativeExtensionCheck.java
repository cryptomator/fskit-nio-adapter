package org.cryptomator.frontend.fskit.mount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;

import static org.cryptomator.frontend.fskit.mount.NativeLibrary.invoke;

/**
 * Checks the app through {@link NativeLibrary}. FSKit lists an extension only to a process of its team.
 * Every check asks the library anew, so it sees an extension that was switched on in the meantime.
 */
class NativeExtensionCheck implements ExtensionCheck {

	private static final Logger LOG = LoggerFactory.getLogger(NativeExtensionCheck.class);
	static final NativeExtensionCheck SHARED = new NativeExtensionCheck(NativeLibrary.SHARED);
	private static final int TIMEOUT_MILLIS = 2000;

	// the codes of ExtensionStatus in the library
	private static final int NOT_IN_APP = 0;
	private static final int NOT_EMBEDDED = 1;
	private static final int EMBEDDED = 2;
	private static final int NOT_REGISTERED = 3;
	private static final int DISABLED = 4;
	private static final int ENABLED = 5;
	private static final int FSKIT_FAILED = -1;
	private static final int TIMED_OUT = -2;

	private final NativeLibrary library;

	NativeExtensionCheck(NativeLibrary library) {
		this.library = library;
	}

	@Override
	public Status embedded(String fsType) {
		NativeLibrary.Functions functions = library.functions();
		if (functions == null) {
			return Status.FAILED;
		}
		try (Arena arena = Arena.ofConfined()) {
			return decode(invoke(functions.embedded(), arena.allocateFrom(fsType)));
		}
	}

	@Override
	public Status status(String fsType) {
		NativeLibrary.Functions functions = library.functions();
		if (functions == null) {
			return Status.FAILED;
		}
		try (Arena arena = Arena.ofConfined()) {
			return decode(invoke(functions.status(), arena.allocateFrom(fsType), TIMEOUT_MILLIS));
		}
	}

	@Override
	public boolean openSettings() {
		NativeLibrary.Functions functions = library.functions();
		return functions != null && invoke(functions.openSettings()) == 1;
	}

	Status decode(int code) {
		return switch (code) {
			case NOT_IN_APP -> Status.NOT_IN_APP;
			case NOT_EMBEDDED -> Status.NOT_EMBEDDED;
			case EMBEDDED -> Status.EMBEDDED;
			case NOT_REGISTERED -> Status.NOT_REGISTERED;
			case DISABLED -> Status.DISABLED;
			case ENABLED -> Status.ENABLED;
			case FSKIT_FAILED -> {
				LOG.warn("FSKit failed to list the installed extensions");
				yield Status.FAILED;
			}
			case TIMED_OUT -> {
				LOG.warn("FSKit did not list the installed extensions within {} ms", TIMEOUT_MILLIS);
				yield Status.FAILED;
			}
			default -> {
				LOG.warn("{} answered the unknown code {}", library.name(), code);
				yield Status.FAILED;
			}
		};
	}
}
