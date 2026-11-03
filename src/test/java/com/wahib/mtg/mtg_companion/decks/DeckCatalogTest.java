package com.wahib.mtg.mtg_companion.decks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class DeckCatalogTest {

	private static final Map<String, String> SET_NAMES = Map.of("fic", "Final Fantasy Commander", "ltc",
			"Tales of Middle-earth Commander");

	private static List<DeckCatalog.Entry> catalog() throws Exception {
		try (InputStream input = DeckCatalogTest.class.getResourceAsStream("/mtgjson/DeckList.json")) {
			var data = JsonMapper.builder().build().readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8)).get("data");
			return DeckCatalog.newestFirst(DeckCatalog.withoutCollectorsEditions(DeckCatalog.commanderDecks(data)));
		}
	}

	private static List<String> ids(List<DeckSummary> decks) {
		return decks.stream().map(DeckSummary::id).toList();
	}

	@Test
	void onlyOfficialCommanderPreconsAreKept() throws Exception {
		List<String> ids = catalog().stream().map(DeckCatalog.Entry::fileName).toList();

		assertThat(ids).doesNotContain("JumpstartRed_JMP", "ThemeDeck_DKM", "MtgoCommander_MTG", "BundleLandPack_CLB");
		assertThat(ids).contains("FoodAndFellowship_LTC", "BuiltFromScratch_C14");
	}

	@Test
	void collectorsEditionsAreHiddenWhenAStandardVersionExists() throws Exception {
		List<String> ids = catalog().stream().map(DeckCatalog.Entry::fileName).toList();

		assertThat(ids).noneMatch(id -> id.contains("CollectorSEdition_FIC"));
		// Aucune version standard : on ne perd pas le deck
		assertThat(ids).contains("LonelyDeckCollectorSEdition_ZZZ");
	}

	@Test
	void newestDecksComeFirst() throws Exception {
		List<DeckCatalog.Entry> entries = catalog();

		assertThat(entries.getFirst().fileName()).isEqualTo("HeadsIWinTailsYouLose_SLD");
		assertThat(entries.getLast().fileName()).isEqualTo("BuiltFromScratch_C14");
	}

	@Test
	void searchingTheFranchiseFindsTheFourFinalFantasyDecks() throws Exception {
		List<DeckSummary> found = DeckCatalog.search(catalog(), "final fantasy", SET_NAMES, Map.of());

		assertThat(found).extracting(DeckSummary::name).containsExactlyInAnyOrder(
				"Limit Break (FINAL FANTASY VII)", "Counter Blitz (FINAL FANTASY X)",
				"Scions & Spellcraft (FINAL FANTASY XIV)", "Revival Trance (FINAL FANTASY VI)");
	}

	@Test
	void searchMatchesSetNameSetCodeAndDeckNameIgnoringCaseAndAccents() throws Exception {
		assertThat(DeckCatalog.search(catalog(), "FINAL FANTASY COMMANDER", SET_NAMES, Map.of())).hasSize(4);
		assertThat(DeckCatalog.search(catalog(), "fic", SET_NAMES, Map.of())).hasSize(4);
		assertThat(ids(DeckCatalog.search(catalog(), "FÖOD fellowship", SET_NAMES, Map.of())))
				.containsExactly("FoodAndFellowship_LTC");
		assertThat(ids(DeckCatalog.search(catalog(), "food fellowship", SET_NAMES, Map.of())))
				.containsExactly("FoodAndFellowship_LTC");
		assertThat(DeckCatalog.search(catalog(), "tales middle-earth", SET_NAMES, Map.of())).hasSize(1);
	}

	@Test
	void searchMatchesKnownCommandersOnly() throws Exception {
		Map<String, List<String>> index = Map.of("LimitBreakFinalFantasyVii_FIC", List.of("Cloud, Ex-SOLDIER"));

		assertThat(ids(DeckCatalog.search(catalog(), "cloud", SET_NAMES, index))).containsExactly("LimitBreakFinalFantasyVii_FIC");
		assertThat(DeckCatalog.search(catalog(), "tidus", SET_NAMES, index)).isEmpty();
	}

	@Test
	void emptySearchKeepsEveryDeckInOrder() throws Exception {
		assertThat(DeckCatalog.search(catalog(), "  ", SET_NAMES, Map.of())).hasSize(catalog().size());
	}
}
