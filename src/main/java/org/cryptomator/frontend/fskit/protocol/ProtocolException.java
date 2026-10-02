package org.cryptomator.frontend.fskit.protocol;

import java.io.IOException;

/**
 * Thrown when a frame, message or manifest violates the bridge protocol. A connection that carried it must be closed.
 */
public class ProtocolException extends IOException {

	public ProtocolException(String msg) {
		super(msg);
	}

	public ProtocolException(String msg, Exception cause) {
		super(msg, cause);
	}
}
