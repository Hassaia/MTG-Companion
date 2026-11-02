package com.wahib.mtg.mtg_companion.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.wahib.mtg.mtg_companion.cards.CardSuggestions.LocalizedName;
import com.wahib.mtg.mtg_companion.cards.CardSuggestions.Suggestion;

class CardSuggestionsTest {

	@Test
	void frenchMatchesComeBeforeEnglishOnesAtEqualRank() {
		List<Suggestion> suggestions = CardSuggestions.merge("fou",
				List.of(new LocalizedName("Lightning Bolt", "Foudre")),
				List.of("Foul Imp"));

		assertThat(suggestions).containsExactly(
				new Suggestion("Lightning Bolt", "Foudre"),
				new Suggestion("Foul Imp", "Foul Imp"));
	}

	@Test
	void namesStartingWithTheQueryComeFirst() {
		List<Suggestion> suggestions = CardSuggestions.merge("foud",
				List.of(new LocalizedName("Javelin of Lightning", "Javeline de foudre"),
						new LocalizedName("Judgment Bolt", "Foudre du jugement"),
						new LocalizedName("Boltwave", "Ondefoudre")),
				List.of());

		assertThat(suggestions).extracting(Suggestion::label)
				.containsExactly("Foudre du jugement", "Javeline de foudre", "Ondefoudre");
	}

	@Test
	void ignoresAccentsCaseAndPunctuation() {
		List<Suggestion> suggestions = CardSuggestions.merge("GEANT CRAQUE",
				List.of(new LocalizedName("Bonecrusher Giant // Stomp", "Géant craque-os // Piétineur")),
				List.of());

		assertThat(suggestions).extracting(Suggestion::label).containsExactly("Géant craque-os // Piétineur");
	}

	@Test
	void sameCardFromBothSourcesAppearsOnceWithItsFrenchName() {
		List<Suggestion> suggestions = CardSuggestions.merge("light",
				List.of(new LocalizedName("Lightning Bolt", "Foudre")),
				List.of("Lightning Bolt", "Lightning Axe"));

		// À rang égal (les deux noms anglais commencent par « light »), la carte traduite passe d'abord
		assertThat(suggestions).containsExactly(
				new Suggestion("Lightning Bolt", "Foudre"),
				new Suggestion("Lightning Axe", "Lightning Axe"));
	}

	@Test
	void untranslatedFrenchResultIsLabelledWithItsEnglishName() {
		List<Suggestion> suggestions = CardSuggestions.merge("delver",
				List.of(new LocalizedName("Delver of Secrets // Insectile Aberration",
						"Delver of Secrets // Insectile Aberration")),
				List.of());

		assertThat(suggestions.getFirst().label()).isEqualTo("Delver of Secrets // Insectile Aberration");
	}

	@Test
	void isLimitedToTenSuggestions() {
		List<String> names = IntStream.range(0, 20).mapToObj(index -> "Light " + index).toList();

		assertThat(CardSuggestions.merge("light", List.of(), names)).hasSize(CardSuggestions.MAX_SUGGESTIONS);
	}

	@Test
	void offlineSuggestionsKeepOnlyMatchingKnownCards() {
		List<LocalizedName> known = List.of(
				new LocalizedName("Lightning Bolt", "Foudre"),
				new LocalizedName("Delver of Secrets // Insectile Aberration", "Sondeur de secrets // Aberration insectoïde"));

		assertThat(CardSuggestions.filterKnown("sondeur", known)).extracting(Suggestion::name)
				.containsExactly("Delver of Secrets // Insectile Aberration");
		assertThat(CardSuggestions.filterKnown("bolt", known)).extracting(Suggestion::label)
				.containsExactly("Foudre");
		assertThat(CardSuggestions.filterKnown("xyz", known)).isEmpty();
	}
}
