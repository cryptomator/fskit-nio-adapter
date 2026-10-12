package org.cryptomator.frontend.fskit.mount;

import org.cryptomator.integrations.mount.MountFailedException;

import java.nio.file.Path;

/**
 * Mounts through FSKit at a path the system chooses in {@code /Volumes}. Tests substitute it.
 */
interface VolumeMounter {

	/**
	 * @param fsType        The file system type, which the extension declares as its {@code FSShortName}
	 * @param rendezvousDir The directory that holds the manifest of the session to mount
	 * @return The path the volume is mounted at
	 */
	Path mount(String fsType, Path rendezvousDir, boolean readOnly) throws MountFailedException;
}
