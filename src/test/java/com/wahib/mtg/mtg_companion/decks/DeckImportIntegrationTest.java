package com.wahib.mtg.mtg_companion.decks;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.wahib.mtg.mtg_companion.cards.FakeScryfallServer;
import com.wahib.mtg.mtg_companion.game.Format;
import com.wahib.mtg.mtg_companion.server.ClientMessage;
import com.wahib.mtg.mtg_companion.server.GameService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Un deck importé se choisit et se consulte comme un préconstruit, et sa suppression libère les joueurs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeckImportIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String LIMIT_BREAK = "LimitBreakFinalFantasyVii_FIC";

	@TempDir
	static Path directory;

	static FakeMtgJsonServer mtgJson;
	static FakeScryfallServer scryfall;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws Exception {
		mtgJson = new FakeMtgJsonServer();
		scryfall = new FakeScryfallServer();
		scryfall.stubBody("/sets", Map.of(), "{\"object\":\"list\",\"data\":[]}")
				.stubBody("/cards/collection", Map.of(), """
						{"object":"list","not_found":[],"data":[
						{"id":"id-sheoldred","name":"Sheoldred, the Apocalypse","set":"dmu","collector_number":"369"},
						{"id":"id-sol-ring","name":"Sol Ring","set":"fdc","collector_number":"286"}]}""");
		registry.add("mtg.mtgjson.base-url", mtgJson::baseUrl);
		registry.add("mtg.scryfall.base-url", scryfall::baseUrl);
		registry.add("mtg.deck-cache-dir", () -> directory.resolve("deck-cache").toString());
		registry.add("mtg.custom-decks-dir", () -> directory.resolve("custom-decks").toString());
		registry.add("mtg.card-cache-dir", () -> directory.resolve("card-cache").toString());
		registry.add("mtg.state-file", () -> directory.resolve("state.json").toString());
		registry.add("mtg.player-art-file", () -> directory.resolve("player-art.json").toString());
		registry.add("mtg.user-images-dir", () -> directory.resolve("user-images").toString());
	}

	@AfterAll
	static void stopServers() {
		mtgJson.close();
		scryfall.close();
	}

	@Value("${local.server.port}")
	int port;

	@Autowired
	GameService gameService;

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private HttpResponse<String> send(String method, String path, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json");
		request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode json(HttpResponse<String> response) {
		return JSON.readTree(response.body());
	}

	private String importDeck(String name) throws Exception {
		String body = JSON.writeValueAsString(Map.of("text", "1 Sheoldred, the Apocalypse (DMU) 369\n1 Sol Ring (FDC) 286",
				"name", name, "commanderLines", List.of(0), "ignoreProblems", false));
		HttpResponse<String> response = send("POST", "/api/decks/custom", body);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json(response).get("id").asString();
	}

	@Test
	void importedDeckIsListedSeparatelyFromOfficialDecksAndReadableLikeThem() throws Exception {
		String id = importDeck("Mon Sheoldred");

		JsonNode custom = json(send("GET", "/api/decks?origin=CUSTOM&q=mon%20sheoldred", null));
		assertThat(custom).hasSize(1);
		assertThat(custom.get(0).get("id").asString()).isEqualTo(id);
		assertThat(custom.get(0).get("origin").asString()).isEqualTo("CUSTOM");

		JsonNode official = json(send("GET", "/api/decks?q=limit", null));
		assertThat(official.get(0).get("id").asString()).isEqualTo(LIMIT_BREAK);
		assertThat(official.get(0).get("origin").asString()).isEqualTo("MTGJSON");
		assertThat(json(send("GET", "/api/decks?q=sheoldred", null))).isEmpty();

		JsonNode deck = json(send("GET", "/api/decks/" + id, null));
		assertThat(deck.get("commanders").get(0).get("name").asString()).isEqualTo("Sheoldred, the Apocalypse");
		assertThat(deck.get("cards").get(0).get("printId").asString()).isEqualTo("id-sol-ring");
	}

	@Test
	void importedAndOfficialDecksAreBothShownInPlayAndDeletingReleasesThePlayer() throws Exception {
		String id = importDeck("Deck en jeu");
		gameService.handle(new ClientMessage.StartGame(Format.COMMANDER, 40, List.of("Alice", "Bob")));
		gameService.handle(new ClientMessage.SetPlayerDeck(1, id));
		gameService.handle(new ClientMessage.SetPlayerDeck(2, LIMIT_BREAK));

		JsonNode inPlay = json(send("GET", "/api/decks/in-play", null));
		assertThat(inPlay).hasSize(2);
		assertThat(inPlay.get(0).get("deckName").asString()).isEqualTo("Deck en jeu");
		assertThat(inPlay.get(0).get("commanders").get(0).asString()).isEqualTo("Sheoldred, the Apocalypse");
		assertThat(inPlay.get(1).get("deckId").asString()).isEqualTo(LIMIT_BREAK);

		assertThat(send("DELETE", "/api/decks/custom/" + id, null).statusCode()).isEqualTo(204);

		assertThat(gameService.snapshot().players().get(0).deckId()).isNull();
		assertThat(gameService.snapshot().players().get(1).deckId()).isEqualTo(LIMIT_BREAK);
		assertThat(send("GET", "/api/decks/" + id, null).statusCode()).isEqualTo(404);
		assertThat(send("DELETE", "/api/decks/custom/" + id, null).statusCode()).isEqualTo(404);
	}

	@Test
	void duplicateNameAnswersConflictWithTheExistingDeck() throws Exception {
		String id = importDeck("Doublon");

		HttpResponse<String> response = send("POST", "/api/decks/custom", JSON.writeValueAsString(
				Map.of("text", "1 Sol Ring (FDC) 286", "name", "doublon", "commanderLines", List.of(), "ignoreProblems", false)));

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(json(response).get("existingId").asString()).isEqualTo(id);
	}

	@Test
	void unknownDeckSelectionAndRenameAreRefused() throws Exception {
		assertThat(send("PATCH", "/api/decks/custom/custom_" + "0".repeat(32), "{\"name\":\"x\"}").statusCode()).isEqualTo(404);
		String id = importDeck("À renommer");

		HttpResponse<String> renamed = send("PATCH", "/api/decks/custom/" + id, "{\"name\":\"Renommé\"}");

		assertThat(renamed.statusCode()).isEqualTo(200);
		assertThat(json(send("GET", "/api/decks?origin=CUSTOM&q=renomm", null)).get(0).get("id").asString()).isEqualTo(id);
	}
}
