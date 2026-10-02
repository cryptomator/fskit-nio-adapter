package org.cryptomator.frontend.fskit.fs;

import org.slf4j.Logger;

/**
 * Logs failures of the backing file system. Their exceptions name the paths involved, which are confidential on a vault, so they are logged in full only at debug level.
 */
final class FailureLog {

	private FailureLog() {
	}

	static void warn(Logger log, String message, Throwable e) {
		if (log.isDebugEnabled()) {
			log.debug(message, e);
		} else {
			log.warn("{} ({})", message, e.getClass().getName());
		}
	}
}
