import Foundation

/// The name a volume starts under, from which `fskitd` names the mount point it creates in `/Volumes`. It refuses to create one for a name that is not ASCII, so a volume takes its own name only once it is mounted.
public enum MountPointName {
	/// The longest name a volume starts under: `NAME_MAX`, less room for the number `fskitd` appends to a taken name.
	static let maxLength = Int(NAME_MAX) - 5

	/// The name transliterated to Latin script and then ASCII, and cut to `maxLength`. Every Unicode scalar without an ASCII form becomes `_`, as does every `/`, and a name that comes out as `.` or `..` becomes `_`.
	public static func of(_ volumeName: String) -> String {
		let transliterated = volumeName.applyingTransform(StringTransform("Any-Latin; Latin-ASCII"), reverse: false) ?? volumeName
		let name = String(String.UnicodeScalarView(transliterated.unicodeScalars.prefix(maxLength).map { $0.isASCII && $0 != "/" ? $0 : "_" }))
		return name == "." || name == ".." ? "_" : name
	}
}
