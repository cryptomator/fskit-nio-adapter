package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.frontend.fskit.mount.ExtensionCheck.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class NativeExtensionCheckTest {

	@Test
	@EnabledIfSystemProperty(named = "fskitNative", matches = "true", disabledReason = "built without the profile fskit-native, which builds the library")
	@DisplayName("the library built for the jar answers that the JVM runs from no app")
	public void testOutsideApp() {
		NativeExtensionCheck check = new NativeExtensionCheck(NativeExtensionCheck.LIBRARY);

		Assertions.assertEquals(Status.NOT_IN_APP, check.embedded("cryptomatorfs"));
		Assertions.assertEquals(Status.NOT_IN_APP, check.status("cryptomatorfs"));
	}

	// the codes the Swift test of ExtensionStatus pins
	@ParameterizedTest(name = "{0} -> {1}")
	@DisplayName("decodes the codes of ExtensionStatus, and every other code as FAILED")
	@CsvSource(value = {"0, NOT_IN_APP", "1, NOT_EMBEDDED", "2, EMBEDDED", "3, NOT_REGISTERED", "4, DISABLED", "5, ENABLED", "-1, FAILED", "-2, FAILED", "6, FAILED"})
	public void testCodes(int code, Status expected) {
		Assertions.assertEquals(expected, new NativeExtensionCheck(NativeExtensionCheck.LIBRARY).decode(code));
	}

	@Test
	@DisplayName("without its library, every check fails, and the failure to load it is logged once")
	public void testMissingLibrary() {
		NativeExtensionCheck check = new NativeExtensionCheck("missing.dylib");
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		PrintStream stderr = System.err;
		// slf4j-simple looks up System.err for every message
		System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
		try {
			Assertions.assertEquals(Status.FAILED, check.embedded("cryptomatorfs"));
			Assertions.assertEquals(Status.FAILED, check.status("cryptomatorfs"));
			Assertions.assertFalse(check.openSettings());
		} finally {
			System.setErr(stderr);
		}

		Assertions.assertEquals(1, log.toString(StandardCharsets.UTF_8).split("Failed to load missing.dylib", -1).length - 1, log.toString(StandardCharsets.UTF_8));
	}
}
