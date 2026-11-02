package com.wahib.mtg.mtg_companion.cards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class ScryfallClientTest {

	private FakeScryfallServer scryfall;
	private ScryfallClient client;

	@BeforeEach
	void start() throws Exception {
		scryfall = new FakeScryfallServer();
		client = new ScryfallClient(scryfall.baseUrl(), ScryfallFixtures.JSON);
	}

	@AfterEach
	void stop() {
		scryfall.close();
	}

	@Test
	void sendsExplicitUserAgentAndAcceptHeaders() {
		scryfall.stub("/cards/autocomplete", Map.of("q", "light"), 200, "autocomplete-light.json");

		client.autocomplete("light");

		FakeScryfallServer.ReceivedRequest request = scryfall.requests().getFirst();
		assertThat(request.userAgent()).isEqualTo(ScryfallClient.USER_AGENT);
		assertThat(request.accept()).isEqualTo("application/json");
	}

	@Test
	void autocompleteReturnsEnglishNames() {
		scryfall.stub("/cards/autocomplete", Map.of("q", "light"), 200, "autocomplete-light.json");

		assertThat(client.autocomplete("light")).contains("Lightning Axe", "Light 'Em Up").hasSize(10);
	}

	@Test
	void findByExactNameReturnsTheCard() {
		scryfall.stub("/cards/named", Map.of("exact", "Lightning Bolt"), 200, "named-lightning-bolt.json");

		assertThat(client.findByExactName("Lightning Bolt"))
				.hasValueSatisfying(card -> assertThat(card.get("name").asString()).isEqualTo("Lightning Bolt"));
	}

	@Test
	void unknownCardIsEmptyRatherThanAnError() {
		assertThat(client.findByExactName("Carte qui n existe pas")).isEmpty();
	}

	@Test
	void searchWithoutResultIsAnEmptyList() {
		scryfall.stub("/cards/search", Map.of(), 404, "fr-prints-none.json");

		assertThat(client.search("oracleid:x lang:fr", true)).isEmpty();
	}

	@Test
	void searchEncodesTheWholeQueryAndAsksForAllPrints() {
		scryfall.stub("/cards/search", Map.of(), 200, "fr-prints-lightning-bolt.json");
		String query = "!\"Lightning Bolt\" lang:fr & co";

		List<JsonNode> prints = client.search(query, true);

		assertThat(prints).hasSize(4);
		assertThat(scryfall.requests().getFirst().parameters())
				.containsEntry("q", query)
				.containsEntry("unique", "prints")
				.containsEntry("order", "released");
	}

	@Test
	void namedAndSearchRequestsAreSpacedByAtLeastHalfASecond() {
		scryfall.stub("/cards/named", Map.of(), 200, "named-lightning-bolt.json");

		client.findByExactName("Lightning Bolt");
		client.findByExactName("Lightning Bolt");

		List<FakeScryfallServer.ReceivedRequest> requests = scryfall.requests();
		assertThat(Duration.ofNanos(requests.get(1).nanoTime() - requests.get(0).nanoTime()))
				.isGreaterThanOrEqualTo(Duration.ofMillis(500));
	}

	@Test
	void tooManyRequestsPausesAllCallsWithoutContactingScryfall() {
		scryfall.stubStatus("/cards/named", 429);

		assertThatThrownBy(() -> client.findByExactName("Lightning Bolt"))
				.isInstanceOf(ScryfallUnavailableException.class)
				.hasMessageContaining("réessayez dans 30 s");
		assertThatThrownBy(() -> client.autocomplete("light"))
				.isInstanceOf(ScryfallUnavailableException.class)
				.hasMessageContaining("réessayez");

		assertThat(scryfall.requests()).hasSize(1);
	}

	@Test
	void serverErrorIsReportedAsUnavailable() {
		scryfall.stubStatus("/cards/autocomplete", 503);

		assertThatThrownBy(() -> client.autocomplete("light"))
				.isInstanceOf(ScryfallUnavailableException.class)
				.hasMessageContaining("HTTP 503");
	}

	@Test
	void unreachableServerGivesAClearMessage() {
		String baseUrl = scryfall.baseUrl();
		scryfall.close();
		ScryfallClient offlineClient = new ScryfallClient(baseUrl, ScryfallFixtures.JSON);

		assertThatThrownBy(() -> offlineClient.autocomplete("light"))
				.isInstanceOf(ScryfallUnavailableException.class)
				.hasMessageContaining("Scryfall ne répond pas");
	}

	@Test
	void downloadsImagesWithTheSameUserAgent() {
		byte[] image = client.downloadImage(scryfall.baseUrl() + "/images/large/front/1/2/abc.jpg");

		assertThat(image).isEqualTo(FakeScryfallServer.IMAGE_BYTES);
		assertThat(scryfall.requests().getFirst().userAgent()).isEqualTo(ScryfallClient.USER_AGENT);
		assertThat(scryfall.requests().getFirst().accept()).isEqualTo("*/*");
	}
}
