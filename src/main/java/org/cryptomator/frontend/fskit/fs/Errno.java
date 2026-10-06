package org.cryptomator.frontend.fskit.fs;

import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.ReadOnlyFileSystemException;

/**
 * macOS errno values.
 */
public final class Errno {

	public static final int EPERM = 1;
	public static final int ENOENT = 2;
	public static final int EIO = 5;
	public static final int EACCES = 13;
	public static final int EBUSY = 16;
	public static final int EEXIST = 17;
	public static final int EXDEV = 18;
	public static final int ENOTDIR = 20;
	public static final int EISDIR = 21;
	public static final int EINVAL = 22;
	public static final int EROFS = 30;
	public static final int EPROTONOSUPPORT = 43;
	public static final int ENOTSUP = 45;
	public static final int ENAMETOOLONG = 63;
	public static final int ENOTEMPTY = 66;
	public static final int ESTALE = 70;

	private Errno() {
	}

	/**
	 * Maps a failed operation to the status of its response.
	 */
	public static int of(Throwable e) {
		return switch (e) {
			case StatusException s -> s.status;
			case NoSuchFileException _ -> ENOENT;
			case FileAlreadyExistsException _ -> EEXIST;
			case DirectoryNotEmptyException _ -> ENOTEMPTY;
			case NotDirectoryException _ -> ENOTDIR;
			case AccessDeniedException _ -> EACCES;
			case ReadOnlyFileSystemException _ -> EROFS;
			case UnsupportedOperationException _ -> ENOTSUP;
			case InvalidPathException _ -> EINVAL;
			case AtomicMoveNotSupportedException _ -> EXDEV;
			default -> EIO;
		};
	}
}
