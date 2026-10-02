package org.cryptomator.frontend.fskit.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.nio.file.NoSuchFileException;

public class FailureLogTest {

	private final Logger log = Mockito.mock(Logger.class);
	private final Exception failure = new NoSuchFileException("/vault/secret name.txt");

	@Test
	@DisplayName("above debug level, logs the message and the exception's class, but nothing that names a path")
	public void testWarn() {
		Mockito.when(log.isDebugEnabled()).thenReturn(false);

		FailureLog.warn(log, "Something failed.", failure);

		Mockito.verify(log).isDebugEnabled();
		Mockito.verify(log).warn("{} ({})", "Something failed.", "java.nio.file.NoSuchFileException");
		Mockito.verifyNoMoreInteractions(log);
	}

	@Test
	@DisplayName("at debug level, logs the exception in full")
	public void testDebug() {
		Mockito.when(log.isDebugEnabled()).thenReturn(true);

		FailureLog.warn(log, "Something failed.", failure);

		Mockito.verify(log).isDebugEnabled();
		Mockito.verify(log).debug("Something failed.", failure);
		Mockito.verifyNoMoreInteractions(log);
	}
}
