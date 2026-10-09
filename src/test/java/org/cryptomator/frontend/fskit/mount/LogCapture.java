package org.cryptomator.frontend.fskit.mount;

import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

final class LogCapture {

	private LogCapture() {
	}

	/**
	 * @return What was logged while the action ran
	 */
	static String of(Executable action) throws Throwable {
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		PrintStream stderr = System.err;
		// slf4j-simple looks up System.err for every message
		System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
		try {
			action.execute();
		} finally {
			System.setErr(stderr);
		}
		return log.toString(StandardCharsets.UTF_8);
	}
}
