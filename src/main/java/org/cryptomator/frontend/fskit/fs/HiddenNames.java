package org.cryptomator.frontend.fskit.fs;

/**
 * The names of AppleDouble companion files ({@code ._<name>}) and of Finder's folder settings ({@code .DS_Store}), which the volume hides as macFUSE's option {@code noappledouble} does.
 * <p>
 * macOS creates no companion files on the volume, since it takes every extended attribute. Because the server hides the names, the extension answers the lookup of a companion, which macOS sends after every first lookup of an entry, without asking.
 */
final class HiddenNames {

	private HiddenNames() {
	}

	/**
	 * @return Whether the name is {@code .DS_Store}, or starts with {@code ._} and is longer than that, compared exactly
	 */
	static boolean contains(String name) {
		return name.equals(".DS_Store") || (name.startsWith("._") && name.length() > 2);
	}
}
