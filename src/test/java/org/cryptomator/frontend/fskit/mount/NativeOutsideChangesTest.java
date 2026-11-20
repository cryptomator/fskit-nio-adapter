package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.cryptofs.CryptoFileSystemProperties;
import org.cryptomator.cryptofs.CryptoFileSystemProvider;
import org.cryptomator.cryptolib.api.Masterkey;
import org.cryptomator.cryptolib.api.MasterkeyLoader;
import org.cryptomator.frontend.fskit.fs.OutsideChanges;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NativeOutsideChangesTest {

	private static final MemorySegment STREAM = MemorySegment.ofAddress(42);

	private MemorySegment started = STREAM;
	private final List<MemorySegment> stopped = new ArrayList<>();
	private @Nullable MemorySegment callback;

	// stand-ins for the library's functions
	@SuppressWarnings("unused")
	private MemorySegment watchStart(MemorySegment path, MemorySegment callback) {
		this.callback = callback;
		return started;
	}

	@SuppressWarnings("unused")
	private void watchStop(MemorySegment stream) {
		stopped.add(stream);
	}

	private NativeLibrary substituted() throws ReflectiveOperationException {
		MethodHandle start = MethodHandles.lookup().findVirtual(NativeOutsideChangesTest.class, "watchStart", MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class)).bindTo(this);
		MethodHandle stop = MethodHandles.lookup().findVirtual(NativeOutsideChangesTest.class, "watchStop", MethodType.methodType(void.class, MemorySegment.class)).bindTo(this);
		return new NativeLibrary("substituted.dylib") {
			@Override
			Functions functionsIfBuiltIn() {
				return new Functions(start, start, start, start, start, stop);
			}
		};
	}

	// calls the upcall stub as native code does, through its address
	@SuppressWarnings("restricted")
	private void streamCallsBack() throws Throwable {
		Linker.nativeLinker().downcallHandle(Objects.requireNonNull(callback), FunctionDescriptor.ofVoid()).invokeExact();
	}

	private static void onAnotherThread(Executable action) throws Throwable {
		AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
		Thread.ofPlatform().start(() -> {
			try {
				action.execute();
			} catch (Throwable e) {
				failure.set(e);
			}
		}).join();
		Throwable thrown = failure.get();
		if (thrown != null) {
			throw thrown;
		}
	}

	@Test
	@DisplayName("a started watch reports every call of the stream, also after a report that threw, and its close stops the stream once")
	public void testStartedWatch(@TempDir Path tmpDir) throws Throwable {
		AtomicInteger reports = new AtomicInteger();
		OutsideChanges changes = Objects.requireNonNull(NativeOutsideChanges.of(substituted(), tmpDir));
		Assertions.assertTrue(changes.start(() -> {
			if (reports.incrementAndGet() == 1) {
				throw new IllegalStateException("failed report");
			}
		}));

		// the stream calls back, and the watch is closed, on threads other than the one that started it
		String log = LogCapture.of(() -> onAnotherThread(() -> {
			streamCallsBack();
			streamCallsBack();
		}));
		onAnotherThread(changes::close);
		onAnotherThread(changes::close);

		Assertions.assertEquals(2, reports.get());
		Assertions.assertTrue(log.contains("failed report"), log);
		Assertions.assertEquals(List.of(STREAM), stopped);
	}

	@Test
	@DisplayName("a stream that does not start leaves no source, and the close stops no stream")
	public void testStreamNotStarted(@TempDir Path tmpDir) throws Throwable {
		started = MemorySegment.NULL;
		OutsideChanges changes = Objects.requireNonNull(NativeOutsideChanges.of(substituted(), tmpDir));

		Assertions.assertFalse(changes.start(() -> {}));
		changes.close();

		Assertions.assertEquals(List.of(), stopped);
	}

	@Test
	@DisplayName("a root of the default file system is watched where it is stored, with links resolved")
	public void testStoredInOfDirectory(@TempDir Path tmpDir) throws IOException {
		Path stored = Files.createDirectory(tmpDir.resolve("stored"));
		Path link = Files.createSymbolicLink(tmpDir.resolve("link"), stored);

		Assertions.assertEquals(stored.toRealPath(), NativeOutsideChanges.storedIn(link));
	}

	@Test
	@DisplayName("a root inside a vault is watched in the vault's directory, also below the vault's root and with a name that needs escaping")
	public void testStoredInOfVault(@TempDir Path tmpDir) throws IOException {
		Path directory = Files.createDirectory(tmpDir.resolve("Tresor ä%"));
		MasterkeyLoader keyLoader = _ -> new Masterkey(new byte[64]);
		CryptoFileSystemProperties properties = CryptoFileSystemProperties.cryptoFileSystemProperties().withKeyLoader(keyLoader).build();
		CryptoFileSystemProvider.initialize(directory, properties, URI.create("test:key"));
		try (FileSystem vault = CryptoFileSystemProvider.newFileSystem(directory, properties)) {
			Files.createDirectory(vault.getPath("/sub"));

			Assertions.assertEquals(directory.toRealPath(), NativeOutsideChanges.storedIn(vault.getPath("/")));
			Assertions.assertEquals(directory.toRealPath(), NativeOutsideChanges.storedIn(vault.getPath("/sub")));
		}
	}

	@Test
	@DisplayName("a build without the library has no source, and says so only at debug level")
	public void testWithoutLibrary(@TempDir Path tmpDir) throws Throwable {
		NativeLibrary missing = new NativeLibrary("missing.dylib");

		String log = LogCapture.of(() -> Assertions.assertNull(NativeOutsideChanges.of(missing, tmpDir)));

		Assertions.assertFalse(log.contains("WARN"), log);
	}
}
