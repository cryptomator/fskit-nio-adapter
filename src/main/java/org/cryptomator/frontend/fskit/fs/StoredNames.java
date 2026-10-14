package org.cryptomator.frontend.fskit.fs;

import org.jetbrains.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Reads the name an entry is stored under from the entry itself, through {@code getattrlist(2)} with {@code ATTR_CMN_NAME}, which macOS answers with search permission on the path alone. Listing the entry's directory would need read permission on it.
 * <p>
 * The call pins the virtual thread that makes it to its carrier for one query of metadata.
 */
final class StoredNames {

	private static final short ATTR_BIT_MAP_COUNT = 5;
	private static final int ATTR_CMN_NAME = 0x1;
	private static final int FSOPT_NOFOLLOW = 0x1;
	private static final int NAME_MAX = 255;
	private static final long ATTRLIST_SIZE = 24;
	// the reply's length, the reference to the name, and the name with its terminator. A name holds up to 255 UTF-16 units, at most 3 bytes each in UTF-8. The kernel pads the name to a multiple of 4 bytes and counts the padding in the reply's length.
	private static final long REPLY_SIZE = 4 + 8 + (NAME_MAX * 3 + 1 + 3) / 4 * 4;
	private static final @Nullable MethodHandle GETATTRLIST = bind();

	private StoredNames() {
	}

	/**
	 * @return The getattrlist function, or {@code null} where the C library has none, as on Linux
	 */
	@SuppressWarnings("restricted")
	private static @Nullable MethodHandle bind() {
		Linker linker = Linker.nativeLinker();
		return linker.defaultLookup().find("getattrlist").map(symbol -> linker.downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT))).orElse(null);
	}

	/**
	 * @param path A path of the default file system
	 * @return The stored name of the entry, not of the target of a symbolic link, or {@code null} if it cannot be read. A mount point answers its volume's name.
	 */
	static @Nullable String of(Path path) {
		if (GETATTRLIST == null) {
			return null;
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment attributes = arena.allocate(ATTRLIST_SIZE, 4);
			attributes.set(JAVA_SHORT, 0, ATTR_BIT_MAP_COUNT);
			attributes.set(JAVA_INT, 4, ATTR_CMN_NAME);
			MemorySegment reply = arena.allocate(REPLY_SIZE, 4);
			int result;
			try {
				result = (int) GETATTRLIST.invokeExact(arena.allocateFrom(path.toString()), attributes, reply, REPLY_SIZE, FSOPT_NOFOLLOW);
			} catch (RuntimeException | Error e) {
				throw e;
			} catch (Throwable e) {
				throw new AssertionError("a downcall throws no checked exception", e);
			}
			return result == 0 ? name(reply) : null;
		}
	}

	/**
	 * @return The name a reply of getattrlist holds, or {@code null} if it holds none that is complete
	 */
	private static @Nullable String name(MemorySegment reply) {
		long length = Integer.toUnsignedLong(reply.get(JAVA_INT, 0));
		// relative to the reference, which follows the length
		long offset = 4 + reply.get(JAVA_INT, 4);
		long nameLength = Integer.toUnsignedLong(reply.get(JAVA_INT, 8));
		if (length > reply.byteSize() || offset < 12 || nameLength < 1 || offset + nameLength > length || reply.get(JAVA_BYTE, offset + nameLength - 1) != 0) {
			return null;
		}
		return new String(reply.asSlice(offset, nameLength - 1).toArray(JAVA_BYTE), StandardCharsets.UTF_8);
	}
}
