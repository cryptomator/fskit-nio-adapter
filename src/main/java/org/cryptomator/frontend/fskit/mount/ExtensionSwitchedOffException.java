package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.integrations.mount.MountFailedException;

/**
 * Thrown by {@code mount()} while the FSKit extension is switched off. By then System Settings has been opened, or has failed to open. Only the user can switch the extension on.
 * <p>
 * Cryptomator recognizes this exception by its fully qualified name, so renaming or moving it breaks that recognition.
 */
public class ExtensionSwitchedOffException extends MountFailedException {

	ExtensionSwitchedOffException(String msg) {
		super(msg);
	}
}
