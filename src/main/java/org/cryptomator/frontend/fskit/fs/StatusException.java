package org.cryptomator.frontend.fskit.fs;

import java.io.IOException;

/**
 * Fails an operation with the given response status.
 */
class StatusException extends IOException {

	final int status;

	StatusException(int status) {
		super("Status " + status);
		this.status = status;
	}
}
