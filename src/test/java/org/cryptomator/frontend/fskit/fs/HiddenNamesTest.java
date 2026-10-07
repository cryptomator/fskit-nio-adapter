package org.cryptomator.frontend.fskit.fs;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

public class HiddenNamesTest {

	// the same table as the Swift test of HiddenNames
	private static Stream<Arguments> names() {
		return Stream.of( //
				Arguments.of(".DS_Store", true), //
				Arguments.of("._a", true), //
				Arguments.of("._._", true), //
				// a combining mark after the underscore, which Swift would join with it into one character
				Arguments.of("._\u0301", true), //
				Arguments.of("._", false), //
				Arguments.of(".ds_store", false), //
				Arguments.of(".DS_Store.txt", false), //
				Arguments.of("a._b", false), //
				Arguments.of("_a", false), //
				Arguments.of(".a", false), //
				Arguments.of("", false));
	}

	@ParameterizedTest(name = "\"{0}\" -> {1}")
	@DisplayName("hides .DS_Store and every name longer than ._ that starts with it, compared exactly")
	@MethodSource("names")
	public void testContains(String name, boolean hidden) {
		Assertions.assertEquals(hidden, HiddenNames.contains(name));
	}
}
