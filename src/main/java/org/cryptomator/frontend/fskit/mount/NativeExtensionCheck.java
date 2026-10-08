package org.cryptomator.frontend.fskit.mount;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Checks the app through the library that {@code fskit/Sources/FSKitNioSupport} builds.
 * The library asks FSKit from within this process, which the app's team signed, and FSKit lists an extension only to a process of its team.
 * Every check asks the library anew, so it sees an extension that was switched on in the meantime.
 */
class NativeExtensionCheck implements ExtensionCheck {

	private static final Logger LOG = LoggerFactory.getLogger(NativeExtensionCheck.class);
	static final String LIBRARY = "libFSKitNioSupport.dylib";
	/**
	 * The check every provider made by the public constructor shares, so that the library is copied and loaded once per process.
	 */
	static final NativeExtensionCheck SHARED = new NativeExtensionCheck(LIBRARY);
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

	private final String resourceName;
	private boolean loadAttempted;
	private @Nullable Library library;

	/**
	 * @param resourceName The library's file name in this class's package
	 */
	NativeExtensionCheck(String resourceName) {
		this.resourceName = resourceName;
	}

	@Override
	public Status embedded(String fsType) {
		Library library = library();
		if (library == null) {
			return Status.FAILED;
		}
		try (Arena arena = Arena.ofConfined()) {
			return decode(invoke(library.embedded(), arena.allocateFrom(fsType)));
		}
	}

	@Override
	public Status status(String fsType) {
		Library library = library();
		if (library == null) {
			return Status.FAILED;
		}
		try (Arena arena = Arena.ofConfined()) {
			return decode(invoke(library.status(), arena.allocateFrom(fsType), TIMEOUT_MILLIS));
		}
	}

	@Override
	public boolean openSettings() {
		Library library = library();
		return library != null && invoke(library.openSettings()) == 1;
	}

	private synchronized @Nullable Library library() {
		if (!loadAttempted) {
			loadAttempted = true;
			try {
				library = Library.load(resourceName);
			} catch (IOException | RuntimeException e) {
				LOG.warn("Failed to load {}, so the FSKit extension cannot be checked", resourceName, e);
			}
		}
		return library;
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
				LOG.warn("{} answered the unknown code {}", resourceName, code);
				yield Status.FAILED;
			}
		};
	}

	private static int invoke(MethodHandle function, Object... arguments) {
		try {
			return (int) function.invokeWithArguments(arguments);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			throw new AssertionError("a downcall throws no checked exception", e);
		}
	}

	private record Library(MethodHandle embedded, MethodHandle status, MethodHandle openSettings) {

		@SuppressWarnings("restricted")
		static Library load(String resourceName) throws IOException {
			Path file = Files.createTempFile("fskitnio", ".dylib");
			file.toFile().deleteOnExit();
			try (InputStream resource = NativeExtensionCheck.class.getResourceAsStream(resourceName)) {
				if (resource == null) {
					throw new FileNotFoundException("No resource " + resourceName);
				}
				Files.copy(resource, file, StandardCopyOption.REPLACE_EXISTING);
			}
			SymbolLookup symbols = SymbolLookup.libraryLookup(file, Arena.global());
			Linker linker = Linker.nativeLinker();
			return new Library( //
					linker.downcallHandle(symbols.findOrThrow("fskitnio_embedded_extension"), FunctionDescriptor.of(JAVA_INT, ADDRESS)), //
					linker.downcallHandle(symbols.findOrThrow("fskitnio_extension_status"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)), //
					linker.downcallHandle(symbols.findOrThrow("fskitnio_open_extension_settings"), FunctionDescriptor.of(JAVA_INT)));
		}
	}
}
