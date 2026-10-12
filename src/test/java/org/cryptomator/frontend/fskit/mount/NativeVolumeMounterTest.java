package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.integrations.mount.MountFailedException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class NativeVolumeMounterTest {

	private final List<Object> mountArguments = new ArrayList<>();

	/**
	 * Stands in for {@code fskitnio_mount}: records its arguments and reports a mount.
	 */
	private int mount(MemorySegment shortName, MemorySegment rendezvousDir, int readOnly, int timeoutMillis, MemorySegment buffer, int bufferSize) {
		mountArguments.addAll(List.of(shortName.getString(0, StandardCharsets.UTF_8), rendezvousDir.getString(0, StandardCharsets.UTF_8), readOnly, timeoutMillis, bufferSize));
		buffer.setString(0, "/Volumes/Tresor 1", StandardCharsets.UTF_8);
		return 0;
	}

	@Test
	@EnabledIfSystemProperty(named = "fskitNative", matches = "true", disabledReason = "built without the profile fskit-native, which builds the library")
	@DisplayName("the library built for the jar refuses to mount from a JVM that runs from no app, without asking FSKit")
	public void testOutsideApp(@TempDir Path rendezvousDir) {
		VolumeMounter mounter = new NativeVolumeMounter(new NativeLibrary(NativeLibrary.LIBRARY), 2000);

		MountFailedException e = Assertions.assertThrows(MountFailedException.class, () -> mounter.mount("cryptomatorfs", rendezvousDir, false));

		Assertions.assertTrue(e.getMessage().endsWith("This process runs from no app that embeds the FSKit extension for cryptomatorfs"), e.getMessage());
	}

	// the codes the Swift test of MountStatus pins
	@ParameterizedTest(name = "{0}")
	@DisplayName("decodes the codes of MountStatus, and fails for every other code")
	@CsvSource(value = {"-1, Failed to mount through FSKit: NSPOSIXErrorDomain 1", "-2, FSKit did not mount within 2000 ms", "1, com.apple.developer.fskit.mount", "2, unknown code 2"})
	public void testFailureCodes(int code, String expectedMessage) {
		NativeVolumeMounter mounter = new NativeVolumeMounter(new NativeLibrary(NativeLibrary.LIBRARY), 2000);

		MountFailedException e = Assertions.assertThrows(MountFailedException.class, () -> mounter.decode(code, "NSPOSIXErrorDomain 1: Operation not permitted"));

		Assertions.assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
	}

	@ParameterizedTest(name = "read-only {0}")
	@DisplayName("passes the file system type, the rendezvous directory, the read-only flag, the timeout and the buffer to the library, and returns the path it reports")
	@ValueSource(booleans = {false, true})
	public void testDowncall(boolean readOnly) throws ReflectiveOperationException, MountFailedException {
		MethodHandle mount = MethodHandles.lookup().findVirtual(NativeVolumeMounterTest.class, "mount", MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class, int.class, int.class, MemorySegment.class, int.class)).bindTo(this);
		NativeLibrary library = new NativeLibrary("substituted.dylib") {
			@Override
			synchronized Functions functions() {
				return new Functions(mount, mount, mount, mount);
			}
		};

		Path mountPoint = new NativeVolumeMounter(library, 2000).mount("cryptomatorfs", Path.of("/tmp/rendezvous"), readOnly);

		Assertions.assertEquals(Path.of("/Volumes/Tresor 1"), mountPoint);
		Assertions.assertEquals(List.of("cryptomatorfs", "/tmp/rendezvous", readOnly ? 1 : 0, 2000, 4096), mountArguments);
	}

	@Test
	@DisplayName("without its library, a mount fails")
	public void testMissingLibrary(@TempDir Path rendezvousDir) {
		VolumeMounter mounter = new NativeVolumeMounter(new NativeLibrary("missing.dylib"), 2000);

		MountFailedException e = Assertions.assertThrows(MountFailedException.class, () -> mounter.mount("cryptomatorfs", rendezvousDir, false));

		Assertions.assertTrue(e.getMessage().contains("Failed to load missing.dylib"), e.getMessage());
	}
}
