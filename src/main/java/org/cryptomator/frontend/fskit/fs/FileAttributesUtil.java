package org.cryptomator.frontend.fskit.fs;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

@SuppressWarnings("OctalInteger")
final class FileAttributesUtil {

	private FileAttributesUtil() {
	}

	static Set<PosixFilePermission> octalModeToPosixPermissions(int mode) {
		Set<PosixFilePermission> result = EnumSet.noneOf(PosixFilePermission.class);
		// @formatter:off
		if ((mode & 0400) == 0400) result.add(PosixFilePermission.OWNER_READ);
		if ((mode & 0200) == 0200) result.add(PosixFilePermission.OWNER_WRITE);
		if ((mode & 0100) == 0100) result.add(PosixFilePermission.OWNER_EXECUTE);
		if ((mode & 0040) == 0040) result.add(PosixFilePermission.GROUP_READ);
		if ((mode & 0020) == 0020) result.add(PosixFilePermission.GROUP_WRITE);
		if ((mode & 0010) == 0010) result.add(PosixFilePermission.GROUP_EXECUTE);
		if ((mode & 0004) == 0004) result.add(PosixFilePermission.OTHERS_READ);
		if ((mode & 0002) == 0002) result.add(PosixFilePermission.OTHERS_WRITE);
		if ((mode & 0001) == 0001) result.add(PosixFilePermission.OTHERS_EXECUTE);
		// @formatter:on
		return result;
	}

	static int posixPermissionsToOctalMode(Set<PosixFilePermission> permissions) {
		int mode = 0;
		// @formatter:off
		if (permissions.contains(PosixFilePermission.OWNER_READ))     mode = mode | 0400;
		if (permissions.contains(PosixFilePermission.GROUP_READ))     mode = mode | 0040;
		if (permissions.contains(PosixFilePermission.OTHERS_READ))    mode = mode | 0004;
		if (permissions.contains(PosixFilePermission.OWNER_WRITE))    mode = mode | 0200;
		if (permissions.contains(PosixFilePermission.GROUP_WRITE))    mode = mode | 0020;
		if (permissions.contains(PosixFilePermission.OTHERS_WRITE))   mode = mode | 0002;
		if (permissions.contains(PosixFilePermission.OWNER_EXECUTE))  mode = mode | 0100;
		if (permissions.contains(PosixFilePermission.GROUP_EXECUTE))  mode = mode | 0010;
		if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) mode = mode | 0001;
		// @formatter:on
		return mode;
	}

}
