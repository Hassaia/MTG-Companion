package com.wahib.mtg.mtg_companion.decks;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.wahib.mtg.mtg_companion.cards.FakeScryfallServer;
import com.wahib.mtg.mtg_companion.cards.ScryfallFixtures;
import com.wahib.mtg.mtg_companion.server.GameStateStore;

import tools.jackson.databind.JsonNode;

/**
 * Parcours complet avec un faux MTGJSON et un faux Scryfall : recherche du catalogue, choix des decks par
 * WebSocket, consultation du deck d'un adversaire, affichage d'une de ses cartes sur la table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeckIntegrationTest {

	private static final long TIMEOUT_SECONDS = 5;
	private static final String LIMIT_BREAK = "LimitBreakFinalFantasyVii_FIC";
	private static final String COUNTER_BLITZ = "CounterBlitzFinalFantasyX_FIC";
	private static final String CLOUD = "Cloud, Ex-SOLDIER";
	private static final String CLOUD_EN_PRINT = "955189c4-cfbc-47be-9681-cfffab403443";
	private static final String CLOUD_FR_PRINT = "bc7a51db-3a36-4387-bdd5-12c05f1cd856";

	@TempDir
	static Path directory;

	static FakeMtgJsonServer mtgJson;
	static FakeScryfallServer scryfall;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws Exception {
		mtgJson = new FakeMtgJsonServer();
		scryfall = new FakeScryfallServer();
		scryfall.stubBody("/sets", Map.of(), """
				{"object":"list","data":[{"code":"fic","name":"Final Fantasy Commander"},{"code":"ltc","name":"Tales of Middle-earth Commander"}]}""")
				.stub("/cards/named", Map.of("exact", CLOUD), 200, "number-fic-168-en.json")
				.stub("/cards/" + CLOUD_EN_PRINT, Map.of(), 200, "number-fic-168-en.json")
				.stub("/cards/fic/168/fr", Map.of(), 200, "number-fic-168-fr.json")
				.stub("/cards/fic/168/en", Map.of(), 200, "number-fic-168-en.json");

		registry.add("mtg.mtgjson.base-url", mtgJson::baseUrl);
		registry.add("mtg.scryfall.base-url", scryfall::baseUrl);
		registry.add("mtg.deck-cache-dir", () -> directory.resolve("deck-cache").toString());
		registry.add("mtg.card-cache-dir", () -> directory.resolve("card-cache").toString());
		registry.add("mtg.state-file", () -> directory.resolve("state.json").toString());
		registry.add("mtg.player-art-file", () -> directory.resolve("player-art.json").toString());
		registry.add("mtg.user-images-dir", () -> directory.resolve("user-images").toString());
		registry.add("mtg.card-display-duration", () -> "2s");
	}

	@AfterAll
	static void stopServers() {
		mtgJson.close();
		scryfall.close();
	}

	@Value("${local.server.port}")
	int port;

	@Autowired
	GameStateStore store;

	private Client phone;
	private Client otherPhone;

	@BeforeEach
	void connectAndStartAnonymousGame() throws Exception {
		phone = connect();
		otherPhone = connect();
		phone.awaitState(game -> true);
		otherPhone.awaitState(game -> true);
		// Des noms qu'aucun test n'utilise : les decks des tests précédents ne sont pas conservés
		phone.send("{\"type\":\"START_GAME\",\"format\":\"COMMANDER\",\"startingLife\":40,\"players\":[\"x1\",\"x2\"]}");
		otherPhone.awaitState(game -> game.get("players").get(0).get("name").asString().equals("x1"));
	}

	@AfterEach
	void disconnect() throws Exception {
		phone.session.close();
		otherPhone.session.close();
		post("/api/cards/hide", "");
	}

	private JsonNode startGame(String... names) throws Exception {
		String players = String.join(",", Arrays.stream(names).map(name -> "\"" + name + "\"").toList());
		phone.send("{\"type\":\"START_GAME\",\"format\":\"COMMANDER\",\"startingLife\":40,\"players\":[" + players + "]}");
		return otherPhone.awaitState(game -> game.get("players").size() == names.length
				&& game.get("players").get(0).get("name").asString().equals(names[0]));
	}

	private static List<String> deckIds(JsonNode game) {
		List<String> ids = new ArrayList<>();
		for (JsonNode player : game.get("players")) {
			ids.add(player.hasNonNull("deckId") ? player.get("deckId").asString() : null);
		}
		return ids;
	}

	@Test
	void catalogFindsTheFinalFantasyDecksByFranchiseEditionNameAndCode() throws Exception {
		for (String query : List.of("final%20fantasy", "Final%20Fantasy%20Commander", "FIC")) {
			JsonNode decks = json(get("/api/decks?q=" + query));

			assertThat(decks).as(query).hasSize(4);
			assertThat(decks.get(0).get("setName").asString()).isEqualTo("Final Fantasy Commander");
		}
		assertThat(json(get("/api/decks?q=jumpstart"))).isEmpty();
	}

	@Test
	void eachPlayerChoosesHisOwnDeckAndOtherDevicesSeeIt() throws Exception {
		startGame("Wahib", "Len", "Marie");

		phone.send("{\"type\":\"SET_PLAYER_DECK\",\"player\":1,\"deckId\":\"" + LIMIT_BREAK + "\"}");
		otherPhone.awaitState(game -> deckIds(game).get(0) != null);
		otherPhone.send("{\"type\":\"SET_PLAYER_DECK\",\"player\":2,\"deckId\":\"" + COUNTER_BLITZ + "\"}");
		JsonNode game = phone.awaitState(state -> deckIds(state).get(1) != null);

		assertThat(deckIds(game)).containsExactly(LIMIT_BREAK, COUNTER_BLITZ, null);
		JsonNode inPlay = json(get("/api/decks/in-play"));
		assertThat(inPlay).hasSize(2);
		assertThat(inPlay.get(0).get("playerName").asString()).isEqualTo("Wahib");
		assertThat(inPlay.get(0).get("deckName").asString()).isEqualTo("Limit Break (FINAL FANTASY VII)");
		assertThat(inPlay.get(0).get("commanders").get(0).asString()).isEqualTo(CLOUD);
		assertThat(inPlay.get(1).get("playerName").asString()).isEqualTo("Len");
		assertThat(inPlay.get(1).get("commanders").get(0).asString()).isEqualTo("Tidus, Yuna's Guardian");

		phone.send("{\"type\":\"CLEAR_PLAYER_DECK\",\"player\":1}");
		JsonNode cleared = otherPhone.awaitState(state -> deckIds(state).get(0) == null);
		assertThat(deckIds(cleared)).containsExactly(null, COUNTER_BLITZ, null);
		assertThat(json(get("/api/decks/in-play"))).hasSize(1);
	}

	@Test
	void selectionIsSavedAndFollowsThePlayerThroughStartGame() throws Exception {
		startGame("Wahib", "Len", "Marie");
		phone.send("{\"type\":\"SET_PLAYER_DECK\",\"player\":1,\"deckId\":\"" + LIMIT_BREAK + "\"}");
		phone.send("{\"type\":\"SET_PLAYER_DECK\",\"player\":3,\"deckId\":\"" + COUNTER_BLITZ + "\"}");
		phone.awaitState(game -> deckIds(game).size() == 3 && deckIds(game).get(2) != null);

		assertThat(store.load().orElseThrow().players()).extracting(player -> player.deckId())
				.containsExactly(LIMIT_BREAK, null, COUNTER_BLITZ);

		// Wahib change de place, Marie est remplacée par Zoé : Zoé n'hérite pas du deck de Marie
		JsonNode game = startGame("Len", "Wahib", "Zoé");

		assertThat(deckIds(game)).containsExactly(null, LIMIT_BREAK, null);
		assertThat(store.load().orElseThrow().players().get(1).deckId()).isEqualTo(LIMIT_BREAK);
	}

	@Test
	void unknownDeckIsRefusedAndReportedToItsSender() throws Exception {
		phone.send("{\"type\":\"SET_PLAYER_DECK\",\"player\":1,\"deckId\":\"Nope_XXX\"}");

		JsonNode error = phone.awaitMessage(message -> message.get("type").asString().equals("ERROR"));
		assertThat(error.get("message").asString()).contains("Deck inconnu");
		assertThat(store.load().orElseThrow().players()).extracting(player -> player.deckId()).containsOnlyNulls();
	}

	@Test
	void opponentDeckIsReadableAndOpeningItDoesNotCallScryfall() throws Exception {
		startGame("Wahib", "Len");
		phone.send("{\"type\":\"SET_PLAYER_DECK\",\"player\":1,\"deckId\":\"" + LIMIT_BREAK + "\"}");
		otherPhone.awaitState(game -> deckIds(game).get(0) != null);
		get("/api/decks/in-play");
		int scryfallRequests = scryfall.requests().size();

		JsonNode deck = json(get("/api/decks/" + LIMIT_BREAK));

		assertThat(deck.get("commanders")).hasSize(1);
		assertThat(deck.get("commanders").get(0).get("printId").asString()).isEqualTo(CLOUD_EN_PRINT);
		assertThat(deck.get("cards").toString()).doesNotContain("Treasure");
		int total = 0;
		for (JsonNode card : deck.get("cards")) {
			total += card.get("count").asInt();
		}
		assertThat(total).isEqualTo(11);
		assertThat(scryfall.requests()).hasSize(scryfallRequests);
		assertThat(get("/api/decks/Nope_XXX").statusCode()).isEqualTo(404);
		assertThat(get("/api/decks/..x").statusCode()).isNotEqualTo(200);
	}

	@Test
	void thumbnailOfADeckCardIsFetchedByItsPrintIdThenServedFromTheCache() throws Exception {
		HttpResponse<byte[]> first = getBytes("/api/cards/thumbnails/" + CLOUD_EN_PRINT + ".jpg");
		long afterFirst = scryfall.requests().size();
		HttpResponse<byte[]> second = getBytes("/api/cards/thumbnails/" + CLOUD_EN_PRINT + ".jpg");

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(first.body()).isEqualTo(FakeScryfallServer.IMAGE_BYTES);
		assertThat(second.body()).isEqualTo(FakeScryfallServer.IMAGE_BYTES);
		assertThat(scryfall.requests()).hasSize((int) afterFirst);
	}

	@Test
	void aCardOfTheDeckIsShownOnTheTableByItsExactPrintInEachLanguageMode() throws Exception {
		showCloud("en");
		JsonNode english = phone.awaitMessage(message -> message.has("card")).get("card");
		showCloud("fr");
		JsonNode french = phone.awaitMessage(message -> message.has("card")).get("card");
		showCloud("both");
		JsonNode both = phone.awaitMessage(message -> message.has("card")).get("card");

		assertThat(english.get("variants")).hasSize(1);
		assertThat(english.get("variants").get(0).get("images").get(0).asString()).contains(CLOUD_EN_PRINT);
		assertThat(french.get("variants").get(0).get("lang").asString()).isEqualTo("fr");
		assertThat(french.get("variants").get(0).get("images").get(0).asString()).contains(CLOUD_FR_PRINT);
		assertThat(both.get("variants")).hasSize(2);
		assertThat(getBytes(both.get("variants").get(1).get("images").get(0).asString()).statusCode()).isEqualTo(200);
	}

	@Test
	void globalSearchIsStillAvailable() throws Exception {
		JsonNode suggestion = json(get("/api/cards/suggestions?q=FIC%20168")).get("suggestions").get(0);

		assertThat(suggestion.get("name").asString()).isEqualTo(CLOUD);
		assertThat(suggestion.get("printId").asString()).isEqualTo(CLOUD_FR_PRINT);
	}

	private void showCloud(String lang) throws Exception {
		HttpResponse<String> response = post("/api/cards/show",
				"{\"name\":\"" + CLOUD + "\",\"printId\":\"" + CLOUD_EN_PRINT + "\",\"lang\":\"" + lang + "\"}");
		assertThat(response.statusCode()).isEqualTo(200);
	}

	private Client connect() throws Exception {
		Client client = new Client();
		client.session = new StandardWebSocketClient()
				.execute(client, "ws://localhost:" + port + "/ws")
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return client;
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

	private static JsonNode json(String text) {
		return ScryfallFixtures.JSON.readTree(text);
	}

	private static class Client extends TextWebSocketHandler {

		final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		WebSocketSession session;

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			messages.add(message.getPayload());
		}

		void send(String json) throws Exception {
			session.sendMessage(new TextMessage(json));
		}

		/** Premier message reçu qui répond au critère ; les précédents sont écartés. */
		JsonNode awaitMessage(Predicate<JsonNode> wanted) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
			while (System.nanoTime() < deadline) {
				String payload = messages.poll(200, TimeUnit.MILLISECONDS);
				if (payload != null) {
					JsonNode message = json(payload);
					if (wanted.test(message)) {
						return message;
					}
				}
			}
			throw new AssertionError("Aucun message attendu reçu avant le délai");
		}

		/** Première diffusion d'état dont la partie répond au critère. */
		JsonNode awaitState(Predicate<JsonNode> wanted) throws InterruptedException {
			return awaitMessage(message -> message.get("type").asString().equals("STATE") && wanted.test(message.get("game")))
					.get("game");
		}
	}
}
