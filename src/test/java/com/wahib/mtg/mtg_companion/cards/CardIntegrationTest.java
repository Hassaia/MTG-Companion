package com.wahib.mtg.mtg_companion.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;

/**
 * Parcours complet avec un faux Scryfall : suggestions, affichage diffusé par WebSocket,
 * images servies depuis le cache, fermeture, erreurs et mode hors connexion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CardIntegrationTest {

	private static final long TIMEOUT_SECONDS = 5;
	private static final String BOLT_FR_PRINT = "1f93e813-516d-43c6-a2f3-4196c18620a5";
	private static final String BOLT_IMAGE = BOLT_FR_PRINT + "-0.jpg";
	/** Illustration de Secret Lair, qui n'existe qu'en anglais. */
	private static final String BOLT_ENGLISH_ONLY_PRINT = "bba5ea18-4e58-42a8-b613-2178706476a6";
	/** Version anglaise dont l'illustration est proposée en français dans la grille : absente de la liste. */
	private static final String BOLT_ENGLISH_DEFAULT_PRINT = "7673784e-db4b-43a1-8d55-1bb9fc1e284f";
	private static final String CLOUD_FR_PRINT = "bc7a51db-3a36-4387-bdd5-12c05f1cd856";

	@TempDir
	static Path directory;

	static FakeScryfallServer scryfall;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws Exception {
		scryfall = new FakeScryfallServer();
		String boltOracleId = ScryfallFixtures.json("named-lightning-bolt.json").get("oracle_id").asString();
		scryfall.stub("/cards/named", Map.of("exact", "Lightning Bolt"), 200, "named-lightning-bolt.json")
				.stub("/cards/search", Map.of("q", "oracleid:" + boltOracleId + " lang:fr"), 200, "fr-prints-lightning-bolt.json")
				.stub("/cards/search", Map.of("q", "oracleid:" + boltOracleId, "unique", "art"), 200, "arts-lightning-bolt.json")
				.stub("/cards/search", Map.of("q", "foud lang:fr"), 200, "search-fr-foud.json")
				.stubBody("/cards/autocomplete", Map.of("q", "foud"), "{\"object\":\"catalog\",\"data\":[]}")
				.stub("/cards/fic/168/fr", Map.of(), 200, "number-fic-168-fr.json")
				.stub("/cards/named", Map.of("exact", "Cloud, Ex-SOLDIER"), 200, "number-fic-168-en.json")
				.stub("/cards/" + BOLT_ENGLISH_DEFAULT_PRINT, Map.of(), 200, "named-lightning-bolt.json")
				.stubStatus("/cards/search", Map.of("q", "foudr lang:fr"), 503)
				.stubStatus("/cards/named", Map.of("exact", "Broken Card"), 503);

		registry.add("mtg.scryfall.base-url", scryfall::baseUrl);
		registry.add("mtg.card-cache-dir", () -> directory.resolve("card-cache").toString());
		registry.add("mtg.state-file", () -> directory.resolve("state.json").toString());
		registry.add("mtg.player-art-file", () -> directory.resolve("player-art.json").toString());
		registry.add("mtg.user-images-dir", () -> directory.resolve("user-images").toString());
		registry.add("mtg.card-display-duration", () -> "2s");
	}

	@AfterAll
	static void stopScryfall() {
		scryfall.close();
	}

	@Value("${local.server.port}")
	int port;

	private TableClient table;

	@BeforeEach
	void connectTable() throws Exception {
		table = new TableClient();
		table.session = new StandardWebSocketClient()
				.execute(table, "ws://localhost:" + port + "/ws")
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThat(table.next().get("type").asString()).isEqualTo("STATE");
	}

	@AfterEach
	void hideCardAndDisconnect() throws Exception {
		post("/api/cards/hide", "");
		table.session.close();
	}

	@Test
	void suggestionsMixFrenchNamesFromScryfall() throws Exception {
		JsonNode response = json(get("/api/cards/suggestions?q=foud"));

		assertThat(response.get("warning").isNull()).isTrue();
		JsonNode first = response.get("suggestions").get(0);
		assertThat(first.get("name").asString()).isEqualTo("Judgment Bolt");
		assertThat(first.get("label").asString()).isEqualTo("Foudre du jugement");
	}

	@Test
	void repeatedQueryIsAnsweredFromMemory() throws Exception {
		get("/api/cards/suggestions?q=foud");
		long requestsBefore = scryfall.requests().size();

		get("/api/cards/suggestions?q=FOUD");

		assertThat(scryfall.requests()).hasSize((int) requestsBefore);
	}

	@Test
	void shownCardIsBroadcastWithImagesServedByThisServer() throws Exception {
		HttpResponse<String> response = post("/api/cards/show", "{\"name\":\"Lightning Bolt\"}");

		assertThat(response.statusCode()).isEqualTo(204);
		JsonNode shown = table.next();
		assertThat(shown.get("type").asString()).isEqualTo("CARD_SHOWN");
		assertThat(shown.get("durationMs").asLong()).isEqualTo(2000);
		JsonNode card = shown.get("card");
		assertThat(card.get("lang").asString()).isEqualTo("fr");
		assertThat(card.get("faces").get(0).get("name").asString()).isEqualTo("Foudre");
		assertThat(card.get("images").get(0).asString()).isEqualTo("/api/cards/images/" + BOLT_IMAGE);

		HttpResponse<byte[]> image = getBytes(card.get("images").get(0).asString());
		assertThat(image.statusCode()).isEqualTo(200);
		assertThat(image.headers().firstValue("Content-Type")).hasValue("image/jpeg");
		assertThat(image.body()).isEqualTo(FakeScryfallServer.IMAGE_BYTES);
	}

	@Test
	void cachedCardIsShownAgainWithoutCallingScryfall() throws Exception {
		post("/api/cards/show", "{\"name\":\"Lightning Bolt\"}");
		table.next();
		long namedRequests = scryfall.countRequests("/cards/named", "exact", "Lightning Bolt");
		long imageRequests = scryfall.requests().stream().filter(request -> request.path().startsWith("/images/")).count();

		post("/api/cards/show", "{\"name\":\"lightning bolt\"}");
		table.next();

		assertThat(scryfall.countRequests("/cards/named", "exact", "Lightning Bolt")).isEqualTo(namedRequests);
		assertThat(scryfall.requests().stream().filter(request -> request.path().startsWith("/images/")).count())
				.isEqualTo(imageRequests);
	}

	@Test
	void hideIsBroadcast() throws Exception {
		post("/api/cards/show", "{\"name\":\"Lightning Bolt\"}");
		table.next();

		assertThat(post("/api/cards/hide", "").statusCode()).isEqualTo(204);

		assertThat(table.next().get("type").asString()).isEqualTo("CARD_HIDDEN");
	}

	@Test
	void cardHidesItselfAfterTheDisplayDuration() throws Exception {
		post("/api/cards/show", "{\"name\":\"Lightning Bolt\"}");
		table.next();

		assertThat(table.next().get("type").asString()).isEqualTo("CARD_HIDDEN");
	}

	@Test
	void screenConnectingLaterReceivesTheDisplayedCard() throws Exception {
		post("/api/cards/show", "{\"name\":\"Lightning Bolt\"}");
		table.next();

		TableClient late = new TableClient();
		late.session = new StandardWebSocketClient()
				.execute(late, "ws://localhost:" + port + "/ws")
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		assertThat(late.next().get("type").asString()).isEqualTo("STATE");
		JsonNode shown = late.next();
		late.session.close();
		assertThat(shown.get("type").asString()).isEqualTo("CARD_SHOWN");
		assertThat(shown.get("remainingMs").asLong()).isBetween(1L, 2000L);
	}

	@Test
	void unknownCardIsReportedOnlyToTheRequester() throws Exception {
		HttpResponse<String> response = post("/api/cards/show", "{\"name\":\"Carte qui n existe pas\"}");

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(json(response).get("message").asString()).contains("Carte introuvable");
		assertThat(table.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
	}

	@Test
	void scryfallFailureGivesAClearMessage() throws Exception {
		HttpResponse<String> response = post("/api/cards/show", "{\"name\":\"Broken Card\"}");

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(json(response).get("message").asString()).contains("Scryfall");
	}

	@Test
	void whenScryfallFailsSuggestionsComeFromCachedCards() throws Exception {
		post("/api/cards/show", "{\"name\":\"Lightning Bolt\"}");
		table.next();

		JsonNode response = json(get("/api/cards/suggestions?q=foudr"));

		assertThat(response.get("warning").asString()).contains("Seules les cartes déjà affichées");
		assertThat(response.get("suggestions").get(0).get("label").asString()).isEqualTo("Foudre");
	}

	@Test
	void illustrationsAreListedWithThumbnailsServedByThisServer() throws Exception {
		JsonNode response = json(get("/api/cards/illustrations?name=" + "Lightning%20Bolt"));

		JsonNode illustrations = response.get("illustrations");
		assertThat(illustrations.size()).isEqualTo(6);
		JsonNode first = illustrations.get(0);
		assertThat(first.get("printId").asString()).isEqualTo(BOLT_FR_PRINT);
		assertThat(first.get("lang").asString()).isEqualTo("fr");
		assertThat(first.get("collectorNumber").asString()).isEqualTo("806");

		HttpResponse<byte[]> thumbnail = getBytes(first.get("thumbnail").asString());
		assertThat(thumbnail.statusCode()).isEqualTo(200);
		assertThat(thumbnail.body()).isEqualTo(FakeScryfallServer.IMAGE_BYTES);
	}

	@Test
	void chosenIllustrationIsShownWithTheFrenchNameKept() throws Exception {
		get("/api/cards/illustrations?name=Lightning%20Bolt");

		post("/api/cards/show", "{\"name\":\"Lightning Bolt\",\"printId\":\"" + BOLT_ENGLISH_ONLY_PRINT + "\"}");

		JsonNode card = table.next().get("card");
		assertThat(card.get("images").get(0).asString()).isEqualTo("/api/cards/images/" + BOLT_ENGLISH_ONLY_PRINT + "-0.jpg");
		assertThat(card.get("faces").get(0).get("name").asString()).isEqualTo("Foudre");
		assertThat(getBytes(card.get("images").get(0).asString()).statusCode()).isEqualTo(200);
	}

	@Test
	void setCodeAndCollectorNumberSuggestThatExactFrenchPrint() throws Exception {
		JsonNode suggestion = json(get("/api/cards/suggestions?q=FIC%20168")).get("suggestions").get(0);

		assertThat(suggestion.get("name").asString()).isEqualTo("Cloud, Ex-SOLDIER");
		assertThat(suggestion.get("label").asString()).isEqualTo("Cloud, ex-SOLDAT");
		assertThat(suggestion.get("printId").asString()).isEqualTo(CLOUD_FR_PRINT);
		assertThat(suggestion.get("printLabel").asString()).isEqualTo("Final Fantasy Commander · n° 168");
	}

	@Test
	void printFoundByNumberIsShownWithItsOwnImage() throws Exception {
		get("/api/cards/suggestions?q=fic%20%23168");

		post("/api/cards/show", "{\"name\":\"Cloud, Ex-SOLDIER\",\"printId\":\"" + CLOUD_FR_PRINT + "\"}");

		JsonNode card = table.next().get("card");
		assertThat(card.get("images").get(0).asString()).isEqualTo("/api/cards/images/" + CLOUD_FR_PRINT + "-0.jpg");
		assertThat(getBytes(card.get("images").get(0).asString()).statusCode()).isEqualTo(200);
	}

	@Test
	void printUnknownToTheCacheIsFetchedById() throws Exception {
		post("/api/cards/show", "{\"name\":\"Lightning Bolt\",\"printId\":\"" + BOLT_ENGLISH_DEFAULT_PRINT + "\"}");

		assertThat(table.next().get("card").get("images").get(0).asString())
				.isEqualTo("/api/cards/images/" + BOLT_ENGLISH_DEFAULT_PRINT + "-0.jpg");
	}

	@Test
	void printOfAnotherCardIsIgnored() throws Exception {
		post("/api/cards/show", "{\"name\":\"Cloud, Ex-SOLDIER\",\"printId\":\"" + BOLT_ENGLISH_DEFAULT_PRINT + "\"}");

		assertThat(table.next().get("card").get("images").get(0).asString())
				.doesNotContain(BOLT_ENGLISH_DEFAULT_PRINT);
	}

	@Test
	void unknownIllustrationFallsBackToTheDefaultOne() throws Exception {
		post("/api/cards/show", "{\"name\":\"Lightning Bolt\",\"printId\":\"00000000-0000-0000-0000-000000000000\"}");

		assertThat(table.next().get("card").get("images").get(0).asString())
				.isEqualTo("/api/cards/images/" + BOLT_IMAGE);
	}

	@Test
	void malformedPrintIdIsRejected() throws Exception {
		assertThat(post("/api/cards/show", "{\"name\":\"Lightning Bolt\",\"printId\":\"../x\"}").statusCode())
				.isEqualTo(400);
	}

	@Test
	void thumbnailOfAnUnlistedPrintIsNotFound() throws Exception {
		assertThat(getBytes("/api/cards/thumbnails/00000000-0000-0000-0000-000000000000.jpg").statusCode())
				.isEqualTo(404);
	}

	@Test
	void invalidImageNamesAreRejected() throws Exception {
		// Tomcat refuse lui-même les barres obliques encodées, avant le contrôleur
		assertThat(getBytes("/api/cards/images/..%2Fstate.json").statusCode()).isEqualTo(400);
		// Un nom qui atteint le contrôleur sans respecter le format attendu n'est jamais lu
		assertThat(getBytes("/api/cards/images/state.json").statusCode()).isEqualTo(404);
	}

	@Test
	void artworkIsCachedOnceServedAndBecomesThePlayerBackground() throws Exception {
		String artist = ScryfallFixtures.data("arts-lightning-bolt.json").stream()
				.filter(print -> BOLT_ENGLISH_ONLY_PRINT.equals(print.get("id").asString()))
				.findFirst().orElseThrow().get("artist").asString();

		JsonNode arts = json(get("/api/cards/arts?name=Lightning%20Bolt&printId=" + BOLT_ENGLISH_ONLY_PRINT));

		assertThat(arts.get("printId").asString()).isEqualTo(BOLT_ENGLISH_ONLY_PRINT);
		assertThat(arts.get("arts").get(0).get("artist").asString()).isEqualTo(artist);
		String imagePath = arts.get("arts").get(0).get("image").asString();
		assertThat(imagePath).isEqualTo("/api/cards/arts/" + BOLT_ENGLISH_ONLY_PRINT + "-0.jpg");
		HttpResponse<byte[]> image = getBytes(imagePath);
		assertThat(image.statusCode()).isEqualTo(200);
		assertThat(image.body()).isEqualTo(FakeScryfallServer.IMAGE_BYTES);

		// Une deuxième demande (réglage de la position, rafraîchissement…) ne retélécharge rien
		long artRequests = scryfall.requests().stream().filter(request -> request.path().contains("/art_crop/")).count();
		assertThat(artRequests).isEqualTo(1);
		get("/api/cards/arts?name=Lightning%20Bolt&printId=" + BOLT_ENGLISH_ONLY_PRINT);
		assertThat(scryfall.requests().stream().filter(request -> request.path().contains("/art_crop/")).count())
				.isEqualTo(artRequests);

		table.session.sendMessage(new TextMessage("""
				{"type":"SET_PLAYER_BACKGROUND","player":1,"cardName":"Lightning Bolt","printId":"%s","face":0,"framings":{"4":{"x":20,"y":80}}}"""
				.formatted(BOLT_ENGLISH_ONLY_PRINT)));

		JsonNode background = table.next().get("game").get("players").get(0).get("background");
		assertThat(background.get("printId").asString()).isEqualTo(BOLT_ENGLISH_ONLY_PRINT);
		assertThat(background.get("artist").asString()).isEqualTo(artist);
		assertThat(background.get("positionX").asInt()).isEqualTo(20);
		assertThat(background.get("positionY").asInt()).isEqualTo(80);

		// Un nouveau client reçoit le fond dès sa connexion
		TableClient late = new TableClient();
		late.session = new StandardWebSocketClient().execute(late, "ws://localhost:" + port + "/ws")
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThat(late.next().get("game").get("players").get(0).get("background").get("printId").asString())
				.isEqualTo(BOLT_ENGLISH_ONLY_PRINT);
		late.session.close();

		table.session.sendMessage(new TextMessage("{\"type\":\"CLEAR_PLAYER_BACKGROUND\",\"player\":1}"));
		assertThat(table.next().get("game").get("players").get(0).get("background").isNull()).isTrue();
	}

	@Test
	void backgroundOfAnArtNotInTheCacheIsRejected() throws Exception {
		table.session.sendMessage(new TextMessage("""
				{"type":"SET_PLAYER_BACKGROUND","player":1,"cardName":"Lightning Bolt","printId":"00000000-0000-0000-0000-000000000000","face":0}"""));

		assertThat(table.next().get("type").asString()).isEqualTo("ERROR");
	}

	@Test
	void invalidArtRequestsAreRejected() throws Exception {
		assertThat(get("/api/cards/arts?name=Lightning%20Bolt&printId=..x").statusCode()).isEqualTo(400);
		assertThat(get("/api/cards/arts?name=").statusCode()).isEqualTo(400);
		assertThat(getBytes("/api/cards/arts/state.json").statusCode()).isEqualTo(404);
	}

	private HttpResponse<String> get(String path) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private HttpResponse<byte[]> getBytes(String path) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofByteArray());
		}
	}

	private HttpResponse<String> post(String path, String jsonBody) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpRequest request = HttpRequest.newBuilder(uri(path))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
					.build();
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private static JsonNode json(HttpResponse<String> response) {
		return ScryfallFixtures.JSON.readTree(response.body());
	}

	private static class TableClient extends TextWebSocketHandler {

		final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		WebSocketSession session;

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			messages.add(message.getPayload());
		}

		JsonNode next() throws InterruptedException {
			String payload = messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThat(payload).as("message reçu avant le délai").isNotNull();
			return ScryfallFixtures.JSON.readTree(payload);
		}
	}
}
