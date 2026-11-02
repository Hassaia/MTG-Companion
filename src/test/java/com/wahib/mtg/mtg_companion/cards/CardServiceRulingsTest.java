package com.wahib.mtg.mtg_companion.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardServiceRulingsTest {

	private static final String PRINT = "1f93e813-516d-43c6-a2f3-4196c18620a5";
	private static final String RULINGS_PATH = "/cards/" + PRINT + "/rulings";

	@TempDir
	Path directory;

	private FakeScryfallServer scryfall;
	private CardCache cache;
	private CardService service;

	@BeforeEach
	void start() throws Exception {
		scryfall = new FakeScryfallServer();
		cache = new CardCache(directory, ScryfallFixtures.JSON);
		service = new CardService(new ScryfallClient(scryfall.baseUrl(), ScryfallFixtures.JSON), cache);
	}

	@AfterEach
	void stop() {
		scryfall.close();
	}

	@Test
	void cardWithSeveralRulingsKeepsScryfallOrder() {
		scryfall.stub(RULINGS_PATH, Map.of(), 200, "rulings-multiple.json");

		CardService.RulingsResult result = service.rulings(PRINT);

		assertThat(result.stale()).isFalse();
		assertThat(result.rulings()).extracting(Ruling::publishedAt)
				.containsExactly("2004-10-04", "2019-01-25", "2009-10-01");
		assertThat(result.rulings().getFirst().source()).isEqualTo("wotc");
		assertThat(result.rulings().getFirst().comment()).startsWith("You can cast");
	}

	@Test
	void cardWithoutRulingIsAnEmptyListNotAnError() {
		scryfall.stub(RULINGS_PATH, Map.of(), 200, "rulings-none.json");

		assertThat(service.rulings(PRINT).rulings()).isEmpty();
	}

	@Test
	void secondConsultationIsServedFromCacheWithoutCallingScryfall() {
		scryfall.stub(RULINGS_PATH, Map.of(), 200, "rulings-multiple.json");

		service.rulings(PRINT);
		CardService.RulingsResult second = service.rulings(PRINT);

		assertThat(second.rulings()).hasSize(3);
		assertThat(scryfall.requests()).hasSize(1);
	}

	@Test
	void emptyListIsCachedToo() {
		scryfall.stub(RULINGS_PATH, Map.of(), 200, "rulings-none.json");

		service.rulings(PRINT);
		service.rulings(PRINT);

		assertThat(scryfall.requests()).hasSize(1);
	}

	@Test
	void expiredCacheIsRefreshed() throws Exception {
		saveCached(Instant.now().minus(CardService.RULINGS_VALIDITY).minus(Duration.ofMinutes(1)),
				List.of(new Ruling("wotc", "2000-01-01", "Ancien")));
		scryfall.stub(RULINGS_PATH, Map.of(), 200, "rulings-multiple.json");

		CardService.RulingsResult result = service.rulings(PRINT);

		assertThat(result.rulings()).hasSize(3);
		assertThat(result.stale()).isFalse();
		assertThat(scryfall.requests()).hasSize(1);
	}

	@Test
	void expiredCacheIsServedWhenScryfallIsUnavailable() throws Exception {
		saveCached(Instant.now().minus(Duration.ofDays(30)), List.of(new Ruling("wotc", "2000-01-01", "Ancien")));
		scryfall.stubStatus(RULINGS_PATH, 503);

		CardService.RulingsResult result = service.rulings(PRINT);

		assertThat(result.stale()).isTrue();
		assertThat(result.rulings()).extracting(Ruling::comment).containsExactly("Ancien");
	}

	@Test
	void scryfallErrorWithoutCacheIsReported() {
		scryfall.stubStatus(RULINGS_PATH, 503);

		assertThatThrownBy(() -> service.rulings(PRINT)).isInstanceOf(ScryfallUnavailableException.class);
	}

	@Test
	void unknownPrintIsNotFoundAndNotCached() {
		// Le faux Scryfall répond 404 par défaut
		assertThatThrownBy(() -> service.rulings(PRINT)).isInstanceOf(CardNotFoundException.class);
		assertThat(cache.findRulings(PRINT)).isEmpty();
	}

	private void saveCached(Instant fetchedAt, List<Ruling> rulings) throws Exception {
		cache.saveRulings(PRINT, new CardCache.CachedRulings(fetchedAt, rulings));
	}
}
