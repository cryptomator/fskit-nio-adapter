package org.cryptomator.frontend.fskit.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * An example from {@code protocol/vectors}: the decoded field values and the encoded bytes.
 */
record Vector(String name, List<String> fields, byte[] bytes) {

	private static final Path DIRECTORY = Path.of("protocol/vectors");
	private static final String HEX_SEPARATOR = "hex:";

	static Vector load(String name) {
		try {
			List<String> lines = Files.readAllLines(DIRECTORY.resolve(name)).stream().filter(line -> !line.startsWith("#")).toList();
			int separator = lines.indexOf(HEX_SEPARATOR);
			return new Vector(name, lines.subList(0, separator), hex(String.join("", lines.subList(separator + 1, lines.size()))));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Parses hex digits, which may be grouped by whitespace.
	 */
	static byte[] hex(String hex) {
		return HexFormat.of().parseHex(hex.replaceAll("\\s", ""));
	}

	/**
	 * All examples that hold a message, i.e. all but the bare frame and the manifest.
	 */
	static Stream<Vector> messages() throws IOException {
		try (Stream<Path> files = Files.list(DIRECTORY)) {
			return files.map(file -> file.getFileName().toString()).filter(name -> !name.equals("frame.txt") && !name.equals("manifest.txt")).sorted().map(Vector::load).toList().stream();
		}
	}

	@Override
	public String toString() {
		return name;
	}

	/**
	 * Lists the fields of a record the way the examples do.
	 */
	static List<String> describe(Record record) {
		List<String> fields = new ArrayList<>();
		describe("", record, fields);
		return fields;
	}

	private static void describe(String name, Object value, List<String> fields) {
		switch (value) {
			case null -> {
				// an absent optional record has no lines
			}
			case Record record -> {
				for (RecordComponent component : record.getClass().getRecordComponents()) {
					try {
						describe(name.isEmpty() ? component.getName() : name + "." + component.getName(), component.getAccessor().invoke(record), fields);
					} catch (ReflectiveOperationException e) {
						throw new IllegalStateException(e);
					}
				}
			}
			case List<?> list -> {
				for (int i = 0; i < list.size(); i++) {
					describe(name + "[" + i + "]", list.get(i), fields);
				}
			}
			case byte[] bytes -> fields.add(name + " = " + HexFormat.of().formatHex(bytes));
			case ByteBuffer buffer -> {
				byte[] bytes = new byte[buffer.remaining()];
				buffer.duplicate().get(bytes);
				fields.add(name + " = " + HexFormat.of().formatHex(bytes));
			}
			case Enum<?> constant -> fields.add(name + " = " + constant.name().toLowerCase(Locale.ROOT));
			default -> fields.add(name + " = " + value);
		}
	}
}
