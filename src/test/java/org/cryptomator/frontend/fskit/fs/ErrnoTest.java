package org.cryptomator.frontend.fskit.fs;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ErrnoTest {

	@Test
	@DisplayName("the statuses are the errno values of macOS")
	public void testValues() {
		Assertions.assertEquals(List.of(2, 5, 13, 16, 17, 18, 20, 21, 22, 30, 43, 45, 66, 70), List.of(Errno.ENOENT, Errno.EIO, Errno.EACCES, Errno.EBUSY, Errno.EEXIST, Errno.EXDEV, Errno.ENOTDIR, Errno.EISDIR, Errno.EINVAL, Errno.EROFS, Errno.EPROTONOSUPPORT, Errno.ENOTSUP, Errno.ENOTEMPTY, Errno.ESTALE));
	}
}
