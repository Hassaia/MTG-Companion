package com.wahib.mtg.mtg_companion.cards;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CollectorNumberQueryTest {

	@ParameterizedTest
	@CsvSource({
			"FIC 168, fic, 168",
			"fic 168, fic, 168",
			"'  FIC   168 ', fic, 168",
			"FIC #168, fic, 168",
			"FIC#168, fic, 168",
			"FIC-168, fic, 168",
			"M10 146, m10, 146",
			"2X2 361, 2x2, 361",
			"ELD 123a, eld, 123a",
	})
	void recognizesSetCodeAndCollectorNumber(String text, String setCode, String collectorNumber) {
		assertThat(CollectorNumberQuery.parse(text)).contains(new CollectorNumberQuery(setCode, collectorNumber));
	}

	@ParameterizedTest
	@ValueSource(strings = {"Foudre", "lightning bolt", "fic168", "FIC", "168", "2022 168", "x 168", "FIC 168 bis",
			"verylongset 1", "FIC 12345", ""})
	void ignoresOtherTexts(String text) {
		assertThat(CollectorNumberQuery.parse(text)).isEmpty();
	}
}
