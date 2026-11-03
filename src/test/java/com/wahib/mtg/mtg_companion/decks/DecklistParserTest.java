package com.wahib.mtg.mtg_companion.decks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.wahib.mtg.mtg_companion.decks.DecklistParser.Line;
import com.wahib.mtg.mtg_companion.decks.DecklistParser.Result;
import com.wahib.mtg.mtg_companion.decks.DecklistParser.Section;

class DecklistParserTest {

	@Test
	void readsTheMoxfieldLineFormats() {
		Result result = DecklistParser.parse("""
				1 Sol Ring
				1 Sol Ring (C21) 263
				1x Sol Ring (C21) 263
				27 Swamp (HOP) 151
				""");

		assertThat(result.lines()).extracting(Line::count).containsExactly(1, 1, 1, 27);
		assertThat(result.lines().get(0)).extracting(Line::name, Line::setCode, Line::collectorNumber)
				.containsExactly("Sol Ring", null, null);
		assertThat(result.lines().get(2)).extracting(Line::name, Line::setCode, Line::collectorNumber)
				.containsExactly("Sol Ring", "c21", "263");
		assertThat(result.unreadable()).isEmpty();
	}

	@Test
	void keepsSpecialCollectorNumbersAndDropsFinishMarks() {
		Result result = DecklistParser.parse("""
				1 Blood Pact (PLST) MID-88
				1 Gixian Puppeteer (PBRO) 99p *F*
				1 Greed (MH2) 274 *E*
				1 Commander's Plate (TMC) 135 *F*
				1 K'rrik, Son of Yawgmoth (MH3) 274
				""");

		assertThat(result.lines()).extracting(Line::name, Line::setCode, Line::collectorNumber).containsExactly(
				org.assertj.core.groups.Tuple.tuple("Blood Pact", "plst", "MID-88"),
				org.assertj.core.groups.Tuple.tuple("Gixian Puppeteer", "pbro", "99p"),
				org.assertj.core.groups.Tuple.tuple("Greed", "mh2", "274"),
				org.assertj.core.groups.Tuple.tuple("Commander's Plate", "tmc", "135"),
				org.assertj.core.groups.Tuple.tuple("K'rrik, Son of Yawgmoth", "mh3", "274"));
	}

	@Test
	void keepsTwoFacedNamesAndNormalizesTheSeparator() {
		Result result = DecklistParser.parse("1 Malakir Rebirth / Malakir Mire (ZNR) 111");

		assertThat(result.lines().get(0).name()).isEqualTo("Malakir Rebirth / Malakir Mire");
		assertThat(DecklistParser.normalizedName("Malakir Rebirth / Malakir Mire")).isEqualTo("Malakir Rebirth // Malakir Mire");
		assertThat(DecklistParser.normalizedName("Delver of Secrets // Insectile Aberration"))
				.isEqualTo("Delver of Secrets // Insectile Aberration");
	}

	@Test
	void separatesCommandersAndSkipsSideboardMaybeboardAndCompanion() {
		Result result = DecklistParser.parse("""
				Commander:
				1 Sheoldred, the Apocalypse (DMU) 369

				MAINBOARD
				1 Sol Ring
				27 Swamp

				Sideboard
				1 Duress

				Maybeboard:
				1 Demonic Tutor

				Companion
				1 Lurrus of the Dream-Den
				""");

		assertThat(result.commandersDeclared()).isTrue();
		assertThat(result.lines()).extracting(Line::name, Line::section).containsExactly(
				org.assertj.core.groups.Tuple.tuple("Sheoldred, the Apocalypse", Section.COMMANDER),
				org.assertj.core.groups.Tuple.tuple("Sol Ring", Section.MAIN),
				org.assertj.core.groups.Tuple.tuple("Swamp", Section.MAIN));
	}

	@Test
	void withoutSectionsNoCommanderIsDeclared() {
		Result result = DecklistParser.parse("1 Sheoldred, the Apocalypse (DMU) 369\n1 Sol Ring (FDC) 286");

		assertThat(result.commandersDeclared()).isFalse();
		assertThat(result.lines()).extracting(Line::section).containsOnly(Section.MAIN);
	}

	@Test
	void reportsUnreadableLines() {
		Result result = DecklistParser.parse("1 Sol Ring\nn'importe quoi\n0 Swamp");

		assertThat(result.lines()).hasSize(1);
		assertThat(result.unreadable()).containsExactly("n'importe quoi", "0 Swamp");
	}

	@Test
	void refusesEmptyOrOversizedLists() {
		assertThatThrownBy(() -> DecklistParser.parse("  \n ")).isInstanceOf(DeckImportService.DeckImportException.class);
		assertThatThrownBy(() -> DecklistParser.parse("1 Sol Ring\n".repeat(DecklistParser.MAX_LINES + 1)))
				.isInstanceOf(DeckImportService.DeckImportException.class);
		assertThatThrownBy(() -> DecklistParser.parse("x".repeat(DecklistParser.MAX_CHARACTERS + 1)))
				.isInstanceOf(DeckImportService.DeckImportException.class);
	}
}
