package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.frontend.fskit.fs.OutsideChanges;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reports changes that other processes make below the directory a volume's root is stored in, through an FSEvents stream of the library {@code FSKitNioSupport}.
 */
final class NativeOutsideChanges implements OutsideChanges {

	private static final Logger LOG = LoggerFactory.getLogger(NativeOutsideChanges.class);
	private static final String CRYPTOFS_URI_SCHEME = "cryptomator";
	private static final MethodHandle REPORT;

	static {
		try {
			REPORT = MethodHandles.lookup().findStatic(NativeOutsideChanges.class, "report", MethodType.methodType(void.class, Runnable.class));
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private final NativeLibrary.Functions functions;
	private final Path watched;
	private @Nullable Arena arena;
	private @Nullable MemorySegment handle;
	private boolean closed;

	private NativeOutsideChanges(NativeLibrary.Functions functions, Path watched) {
		this.functions = functions;
		this.watched = watched;
	}

	/**
	 * @return A source for the root of a volume, or {@code null} if there is none: where this build lacks the library, or where the directory the root is stored in is unknown
	 */
	static @Nullable OutsideChanges of(NativeLibrary library, Path root) {
		Path watched = storedIn(root);
		if (watched == null) {
			LOG.debug("Changes made outside the volume are not reported, since the directory its root is stored in is unknown.");
			return null;
		}
		NativeLibrary.Functions functions = library.functionsIfBuiltIn();
		if (functions == null) {
			LOG.debug("Changes made outside the volume are not reported, since this build lacks {}.", library.name());
			return null;
		}
		return new NativeOutsideChanges(functions, watched);
	}

	/**
	 * @return The directory of the default file system that holds what the root shows: the root itself, or the directory of the vault the root belongs to
	 */
	static @Nullable Path storedIn(Path root) {
		try {
			if (root.getFileSystem() == FileSystems.getDefault()) {
				return root.toRealPath();
			}
			URI uri = root.toUri();
			if (CRYPTOFS_URI_SCHEME.equals(uri.getScheme()) && uri.getAuthority() != null) {
				// the authority is the URI of the vault's directory, as cryptofs reads it
				return Path.of(URI.create(uri.getAuthority())).toRealPath();
			}
		} catch (IOException | RuntimeException e) {
			LOG.debug("Unable to determine the directory a volume's root is stored in.", e);
		}
		return null;
	}

	@Override
	@SuppressWarnings("restricted")
	public synchronized boolean start(Runnable onChange) {
		if (closed || arena != null) {
			throw new IllegalStateException("Started twice or after closing");
		}
		arena = Arena.ofShared();
		MemorySegment callback = Linker.nativeLinker().upcallStub(REPORT.bindTo(onChange), FunctionDescriptor.ofVoid(), arena);
		MemorySegment started = (MemorySegment) Objects.requireNonNull(NativeLibrary.call(functions.watchStart(), arena.allocateFrom(watched.toString()), callback));
		if (started.address() == 0) {
			LOG.debug("Changes made outside the volume are not reported, since the FSEvents stream did not start.");
			return false;
		}
		handle = started;
		return true;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (handle != null) {
			NativeLibrary.call(functions.watchStop(), handle);
			handle = null;
		}
		// once the stream is stopped, nothing calls the stub anymore
		if (arena != null) {
			arena.close();
		}
	}

	// an exception that left an upcall would end the JVM
	@SuppressWarnings("unused")
	private static void report(Runnable onChange) {
		try {
			onChange.run();
		} catch (Throwable e) {
			LOG.error("Failed to report changes made outside the volume.", e);
		}
	}
}
