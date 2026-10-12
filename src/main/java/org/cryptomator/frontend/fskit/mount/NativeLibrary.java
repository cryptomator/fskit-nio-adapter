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
 * The library that {@code fskit/Sources/FSKitNioSupport} builds, loaded on first use.
 * It calls FSKit from within this process, so FSKit sees the app's signature: its team and its entitlements.
 */
class NativeLibrary {

	private static final Logger LOG = LoggerFactory.getLogger(NativeLibrary.class);
	static final String LIBRARY = "libFSKitNioSupport.dylib";
	/**
	 * The library every provider made by the public constructor shares, so that it is copied and loaded once per process.
	 */
	static final NativeLibrary SHARED = new NativeLibrary(LIBRARY);

	private final String resourceName;
	private boolean loadAttempted;
	private @Nullable Functions functions;

	/**
	 * @param resourceName The library's file name in this class's package
	 */
	NativeLibrary(String resourceName) {
		this.resourceName = resourceName;
	}

	String name() {
		return resourceName;
	}

	/**
	 * @return The library's functions, or {@code null} if it failed to load, which has been logged once
	 */
	synchronized @Nullable Functions functions() {
		if (!loadAttempted) {
			loadAttempted = true;
			try {
				functions = Functions.load(resourceName);
			} catch (IOException | RuntimeException e) {
				LOG.warn("Failed to load {}, so the FSKit extension can neither be checked nor mount without a mount point", resourceName, e);
			}
		}
		return functions;
	}

	static int invoke(MethodHandle function, Object... arguments) {
		try {
			return (int) function.invokeWithArguments(arguments);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			throw new AssertionError("a downcall throws no checked exception", e);
		}
	}

	record Functions(MethodHandle embedded, MethodHandle status, MethodHandle openSettings, MethodHandle mount) {

		@SuppressWarnings("restricted")
		static Functions load(String resourceName) throws IOException {
			Path file = Files.createTempFile("fskitnio", ".dylib");
			file.toFile().deleteOnExit();
			try (InputStream resource = NativeLibrary.class.getResourceAsStream(resourceName)) {
				if (resource == null) {
					throw new FileNotFoundException("No resource " + resourceName);
				}
				Files.copy(resource, file, StandardCopyOption.REPLACE_EXISTING);
			}
			SymbolLookup symbols = SymbolLookup.libraryLookup(file, Arena.global());
			Linker linker = Linker.nativeLinker();
			return new Functions( //
					linker.downcallHandle(symbols.findOrThrow("fskitnio_embedded_extension"), FunctionDescriptor.of(JAVA_INT, ADDRESS)), //
					linker.downcallHandle(symbols.findOrThrow("fskitnio_extension_status"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)), //
					linker.downcallHandle(symbols.findOrThrow("fskitnio_open_extension_settings"), FunctionDescriptor.of(JAVA_INT)), //
					linker.downcallHandle(symbols.findOrThrow("fskitnio_mount"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)));
		}
	}
}
