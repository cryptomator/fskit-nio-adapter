package org.cryptomator.frontend.fskit.fs;

import java.io.Closeable;

/**
 * Tells when something else may have changed the backing store of a volume, such as a sync client.
 */
public interface OutsideChanges extends Closeable {

	/**
	 * Starts reporting. Called once.
	 *
	 * @param onChange Runs, on a thread of the source, whenever something may have changed. It says nothing about what changed.
	 * @return Whether the source reports changes from now on
	 */
	boolean start(Runnable onChange);

	/**
	 * Stops reporting. Once this returns, {@code onChange} no longer runs.
	 */
	@Override
	void close();
}
