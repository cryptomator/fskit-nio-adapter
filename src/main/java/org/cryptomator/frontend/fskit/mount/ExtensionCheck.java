package org.cryptomator.frontend.fskit.mount;

/**
 * Checks the app this JVM runs from for the FSKit extension of a file system type. Tests substitute it.
 */
interface ExtensionCheck {

	/**
	 * Looks into the app bundle only, without asking FSKit.
	 *
	 * @param fsType The file system type, which the extension declares as its {@code FSShortName}
	 * @return {@link Status#NOT_IN_APP}, {@link Status#NOT_EMBEDDED}, {@link Status#EMBEDDED} or {@link Status#FAILED}
	 */
	Status embedded(String fsType);

	/**
	 * Looks into the app bundle and asks FSKit about the extension it embeds.
	 *
	 * @param fsType The file system type, which the extension declares as its {@code FSShortName}
	 * @return Any status but {@link Status#EMBEDDED}
	 */
	Status status(String fsType);

	/**
	 * Opens System Settings at File System Extensions.
	 *
	 * @return Whether it opened
	 */
	boolean openSettings();

	enum Status {
		/**
		 * The JVM does not run from an app bundle.
		 */
		NOT_IN_APP,
		NOT_EMBEDDED,
		/**
		 * The app embeds the extension. Only {@link #embedded(String)} answers this.
		 */
		EMBEDDED,
		/**
		 * The app embeds the extension, and FSKit does not list it.
		 */
		NOT_REGISTERED,
		DISABLED,
		ENABLED,
		/**
		 * The check failed, which has been logged.
		 */
		FAILED
	}
}
