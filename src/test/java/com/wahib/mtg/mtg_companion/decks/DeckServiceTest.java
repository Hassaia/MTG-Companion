package com.wahib.mtg.mtg_companion.decks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wahib.mtg.mtg_companion.cards.ScryfallClient;
import com.wahib.mtg.mtg_companion.decks.DeckContent.DeckCard;

import tools.jackson.databind.json.JsonMapper;

class DeckServiceTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String LIMIT_BREAK = "LimitBreakFinalFantasyVii_FIC";

	@TempDir
	Path directory;

	private FakeMtgJsonServer mtgJson;

	@BeforeEach
	void startServer() throws Exception {
		mtgJson = new FakeMtgJsonServer();
	}

	@AfterEach
	void stopServer() {
		mtgJson.close();
	}

	/** Une instance neuve : seul le disque survit d'une instance à l'autre, comme après un redémarrage. */
	private DeckService service() {
		// Les noms d'édition viennent de Scryfall, injoignable ici (port fermé) : ils restent inconnus
		return new DeckService(new MtgJsonClient(mtgJson.baseUrl(), JSON),
				new ScryfallClient("http://localhost:1", JSON), new DeckCache(directory, JSON));
	}

	@Test
	void catalogIsDownloadedOnceThenServedFromMemoryAndDisk() {
		DeckService first = service();
		first.search("");
		first.search("limit");

		assertThat(mtgJson.countRequests("/DeckList.json")).isEqualTo(1);

		service().search("");

		assertThat(mtgJson.countRequests("/DeckList.json")).isEqualTo(1);
	}

	@Test
	void theFourFinalFantasyDecksAreInTheCatalog() {
		assertThat(service().search("final fantasy")).extracting(DeckSummary::id).containsExactlyInAnyOrder(
				LIMIT_BREAK, "CounterBlitzFinalFantasyX_FIC", "ScionsSpellcraftFinalFantasyXiv_FIC",
				"RevivalTranceFinalFantasyVi_FIC");
	}

	@Test
	void deckIsReducedToCommanderAndCardsWithoutTokensNorDuplicates() {
		DeckContent deck = service().deck(LIMIT_BREAK);

		assertThat(deck.commanders()).extracting(DeckCard::name).containsExactly("Cloud, Ex-SOLDIER");
		assertThat(deck.commanders().getFirst().printId()).isEqualTo("955189c4-cfbc-47be-9681-cfffab403443");
		assertThat(deck.cards()).extracting(DeckCard::name).doesNotContain("Treasure", "Cloud, Ex-SOLDIER");
		// Deux lignes de la même impression : une seule ligne, quantités additionnées
		assertThat(deck.cards()).filteredOn(card -> card.name().equals("Plains")).extracting(DeckCard::count)
				.containsExactly(5, 4);
		assertThat(deck.cards()).extracting(DeckCard::printId).doesNotHaveDuplicates();
		assertThat(deck.cards()).extracting(DeckCard::name).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
		assertThat(deck.cards().stream().mapToInt(DeckCard::count).sum() + deck.commanders().size()).isEqualTo(12);
		assertThat(deck.cards().getFirst().setCode()).isEqualTo("fic");
	}

	@Test
	void deckWithSeveralCommandersKeepsThemAllApart() {
		DeckContent deck = service().deck("FoodAndFellowship_LTC");

		assertThat(deck.commanders()).extracting(DeckCard::name)
				.containsExactly("Frodo, Adventurous Hobbit", "Sam, Loyal Attendant");
		assertThat(deck.cards()).extracting(DeckCard::name).containsExactly("Sol Ring");
	}

	@Test
	void deckIsDownloadedOnlyWhenRequestedAndOnlyOnce() {
		DeckService service = service();
		service.search("");
		assertThat(mtgJson.requests()).containsExactly("/DeckList.json");

		service.deck(LIMIT_BREAK);
		service().deck(LIMIT_BREAK);

		assertThat(mtgJson.countRequests("/decks/" + LIMIT_BREAK + ".json")).isEqualTo(1);
	}

	@Test
	void unknownOrInvalidDeckIsRefusedWithoutAnyDownload() {
		DeckService service = service();

		assertThatThrownBy(() -> service.deck("Nope_XXX")).isInstanceOf(DeckNotFoundException.class);
		assertThatThrownBy(() -> service.deck("../secret")).isInstanceOf(DeckNotFoundException.class);
		assertThat(mtgJson.requests()).doesNotContain("/decks/Nope_XXX.json");
	}

	@Test
	void openedDecksFeedTheCommanderIndexThatSurvivesARestart() {
		DeckService service = service();
		assertThat(service.search("cloud")).isEmpty();

		service.deck(LIMIT_BREAK);

		assertThat(service.search("cloud")).extracting(DeckSummary::id).containsExactly(LIMIT_BREAK);
		DeckSummary restarted = service().search("cloud").getFirst();
		assertThat(restarted.commanders()).containsExactly("Cloud, Ex-SOLDIER");
	}

	@Test
	void everythingKeepsWorkingOfflineWithTheCache() {
		DeckService online = service();
		online.search("");
		online.deck(LIMIT_BREAK);
		mtgJson.setOffline(true);

		DeckService offline = service();

		assertThat(offline.search("limit break")).extracting(DeckSummary::id).containsExactly(LIMIT_BREAK);
		assertThat(offline.deck(LIMIT_BREAK).commanders()).hasSize(1);
		// Un deck jamais ouvert ne peut pas être récupéré
		assertThatThrownBy(() -> offline.deck("CounterBlitzFinalFantasyX_FIC")).isInstanceOf(DecksUnavailableException.class);
	}

	@Test
	void expiredCatalogIsRefreshedButTheStaleOneIsKeptWhenMtgJsonIsDown() throws Exception {
		DeckCache cache = new DeckCache(directory, JSON);
		service().search("");
		var cached = cache.catalog().orElseThrow();
		cache.saveCatalog(new DeckCache.CachedCatalog(Instant.now().minus(DeckService.CATALOG_LIFETIME).minusSeconds(60),
				cached.entries().subList(0, 2)));

		DeckService refreshed = service();
		assertThat(refreshed.search("")).hasSize(cached.entries().size());
		assertThat(mtgJson.countRequests("/DeckList.json")).isEqualTo(2);

		cache.saveCatalog(new DeckCache.CachedCatalog(Instant.now().minus(DeckService.CATALOG_LIFETIME).minusSeconds(60),
				cached.entries().subList(0, 2)));
		mtgJson.setOffline(true);
		List<DeckSummary> stale = service().search("");

		assertThat(stale).hasSize(2);
	}

	@Test
	void withoutCacheAndWithoutMtgJsonTheCatalogIsUnavailable() {
		mtgJson.setOffline(true);

		assertThatThrownBy(() -> service().search("")).isInstanceOf(DecksUnavailableException.class);
	}
}
