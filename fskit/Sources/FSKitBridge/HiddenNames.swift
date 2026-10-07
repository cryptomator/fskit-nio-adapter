/// The names of AppleDouble companion files (`._<name>`) and of Finder's folder settings (`.DS_Store`), which the server hides, see `protocol/PROTOCOL.md`.
public enum HiddenNames {
	/// Whether the name is `.DS_Store`, or starts with `._` and is longer than that.
	///
	/// Compares Unicode scalars, which agrees with the server's exact comparison. Comparing characters would join a combining mark with the `_` before it. `==` would also equate canonically equivalent names.
	public static func contains(_ name: String) -> Bool {
		let scalars = name.unicodeScalars
		return scalars.elementsEqual(".DS_Store".unicodeScalars) || (scalars.starts(with: "._".unicodeScalars) && scalars.count > 2)
	}
}
