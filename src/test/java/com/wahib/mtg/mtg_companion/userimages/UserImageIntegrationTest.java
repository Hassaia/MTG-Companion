package com.wahib.mtg.mtg_companion.userimages;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * Parcours complet : import, bibliothèque partagée, fond de zone, cadrages par disposition, suppression,
 * et reprise d'une ancienne sauvegarde de la partie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserImageIntegrationTest {

	private static final long TIMEOUT_SECONDS = 5;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** Joueur 1 de la sauvegarde d'avant les images importées : illustration Scryfall en position 30 / 70. */
	private static final String LEGACY_IMAGE = "bba5ea18-4e58-42a8-b613-2178706476a6-0";

	@TempDir
	static Path directory;

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) throws Exception {
		Files.writeString(directory.resolve("state.json"), """
				{"format":"STANDARD","startingLife":20,"players":[
				 {"id":1,"name":"A","life":20,"eliminated":false,"commanderDamage":{},
				  "background":{"cardName":"Lightning Bolt","printId":"bba5ea18-4e58-42a8-b613-2178706476a6",
				                "face":0,"artist":"Taurin Clarke","positionX":30,"positionY":70}},
				 {"id":2,"name":"B","life":20,"eliminated":false,"commanderDamage":{}},
				 {"id":3,"name":"C","life":20,"eliminated":false,"commanderDamage":{}},
				 {"id":4,"name":"D","life":20,"eliminated":false,"commanderDamage":{}}]}""");
		registry.add("mtg.state-file", () -> directory.resolve("state.json").toString());
		registry.add("mtg.player-art-file", () -> directory.resolve("player-art.json").toString());
		registry.add("mtg.user-images-dir", () -> directory.resolve("user-images").toString());
		registry.add("mtg.card-cache-dir", () -> directory.resolve("card-cache").toString());
	}

	@Value("${local.server.port}")
	int port;

	private Client client;

	@BeforeEach
	void connect() throws Exception {
		client = new Client();
		client.session = new StandardWebSocketClient().execute(client, "ws://localhost:" + port + "/ws")
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThat(client.next().get("type").asString()).isEqualTo("STATE");
	}

	@AfterEach
	void backToFourPlayers() throws Exception {
		startGame(4);
		client.session.close();
	}

	// --- Import et bibliothèque

	@Test
	void uploadedImageIsStoredUntouchedListedAndServedWithItsRealType() throws Exception {
		byte[] original = ImageFormatTest.png(3000, 2000);

		HttpResponse<String> created = post("/api/user-images?name=Mon%20dessin.png", original, "image/png");

		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode view = JSON.readTree(created.body());
		assertThat(view.get("name").asString()).isEqualTo("Mon dessin.png");
		assertThat(view.get("width").asInt()).isEqualTo(3000);
		assertThat(view.get("bytes").asLong()).isEqualTo(original.length);
		HttpResponse<byte[]> served = getBytes(view.get("url").asString());
		assertThat(served.statusCode()).isEqualTo(200);
		assertThat(served.headers().firstValue("Content-Type")).hasValue("image/png");
		assertThat(served.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
		assertThat(served.body()).isEqualTo(original);
		assertThat(JSON.readTree(get("/api/user-images").body()).toString()).contains(view.get("id").asString());
	}

	@Test
	void typeIsDetectedFromTheContentNotFromTheAnnouncedType() throws Exception {
		HttpResponse<String> svg = post("/api/user-images?name=a.png",
				"<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8),
				"image/png");

		assertThat(svg.statusCode()).isEqualTo(415);
		assertThat(JSON.readTree(svg.body()).get("message").asString()).contains("JPEG, PNG ou WebP");
		assertThat(post("/api/user-images?name=a", new byte[0], "image/png").statusCode()).isEqualTo(400);
	}

	@Test
	void oversizedImageIsRefused() throws Exception {
		byte[] tooLarge = java.util.Arrays.copyOf(ImageFormatTest.png(10, 10), (int) PlayerArtService.MAX_BYTES + 1);

		assertThat(post("/api/user-images?name=a.png", tooLarge, "image/png").statusCode()).isEqualTo(413);
	}

	@Test
	void unknownOrMalformedImageIdsAreNotFound() throws Exception {
		assertThat(getBytes("/api/user-images/00000000-0000-0000-0000-000000000000").statusCode()).isEqualTo(404);
		assertThat(getBytes("/api/user-images/state.json").statusCode()).isEqualTo(404);
		assertThat(delete("/api/user-images/00000000-0000-0000-0000-000000000000").statusCode()).isEqualTo(404);
	}

	// --- Fond de zone et cadrages

	@Test
	void framingIsRememberedPerPlayerImageAndLayoutAndSurvivesNewGames() throws Exception {
		String image = upload();
		setUploadBackground(2, image, "{\"2\":{\"x\":10,\"y\":20},\"3\":{\"x\":30,\"y\":40},\"4\":{\"x\":50,\"y\":60}}");
		JsonNode shown = client.next().get("game").get("players").get(1).get("background");
		assertThat(shown.get("kind").asString()).isEqualTo("UPLOAD");
		assertThat(shown.get("imageId").asString()).isEqualTo(image);
		assertThat(position(shown)).containsExactly(50, 60);

		// Un autre joueur règle la même image autrement, sans toucher aux réglages du premier
		setUploadBackground(3, image, "{\"3\":{\"x\":90,\"y\":91}}");
		client.next();

		startGame(3);
		assertThat(positions(client.next(), 1, 2)).containsExactly(30, 40, 90, 91);
		// 4 → 3 → 4 : chaque disposition retrouve son cadrage ; le joueur 3 n'en a pas réglé pour 4 joueurs
		startGame(4);
		assertThat(positions(client.next(), 1, 2)).containsExactly(50, 60, 50, 50);
		startGame(2);
		assertThat(position(client.next().get("game").get("players").get(1).get("background"))).containsExactly(10, 20);
		startGame(4);
		assertThat(position(client.next().get("game").get("players").get(1).get("background"))).containsExactly(50, 60);

		JsonNode saved = JSON.readTree(get("/api/player-art/2/framing").body()).get(image);
		assertThat(saved.get("3").get("x").asInt()).isEqualTo(30);
	}

	@Test
	void removingTheBackgroundKeepsThePreferences() throws Exception {
		String image = upload();
		setUploadBackground(2, image, "{\"4\":{\"x\":15,\"y\":85}}");
		client.next();

		client.send("{\"type\":\"CLEAR_PLAYER_BACKGROUND\",\"player\":2}");
		assertThat(client.next().get("game").get("players").get(1).get("background").isNull()).isTrue();
		assertThat(JSON.readTree(get("/api/player-art/2/framing").body()).has(image)).isTrue();

		setUploadBackground(2, image, "{}");
		assertThat(position(client.next().get("game").get("players").get(1).get("background"))).containsExactly(15, 85);
	}

	@Test
	void deletingAnImageInUseRemovesItsBackgroundAndItsFramings() throws Exception {
		String image = upload();
		setUploadBackground(4, image, "{\"4\":{\"x\":15,\"y\":85}}");
		client.next();

		assertThat(delete("/api/user-images/" + image).statusCode()).isEqualTo(204);

		JsonNode state = client.next();
		assertThat(state.get("game").get("players").get(3).get("background").isNull()).isTrue();
		assertThat(getBytes("/api/user-images/" + image).statusCode()).isEqualTo(404);
		assertThat(get("/api/user-images").body()).doesNotContain(image);
		assertThat(JSON.readTree(get("/api/player-art/4/framing").body()).has(image)).isFalse();
		assertThat(delete("/api/user-images/" + image).statusCode()).isEqualTo(404);
		// Un joueur qui choisit encore cette image reçoit une erreur, pas un fond cassé
		setUploadBackground(4, image, "{}");
		assertThat(client.next().get("type").asString()).isEqualTo("ERROR");
	}

	@Test
	void invalidChoicesAreRefusedWithoutChangingAnything() throws Exception {
		String image = upload();

		setUploadBackground(2, image, "{\"5\":{\"x\":10,\"y\":10}}");
		assertThat(client.next().get("type").asString()).isEqualTo("ERROR");
		setUploadBackground(2, image, "{\"4\":{\"x\":101,\"y\":10}}");
		assertThat(client.next().get("type").asString()).isEqualTo("ERROR");
		client.send("{\"type\":\"SET_PLAYER_BACKGROUND\",\"player\":9,\"kind\":\"UPLOAD\",\"imageId\":\"" + image + "\"}");
		assertThat(client.next().get("type").asString()).isEqualTo("ERROR");
		assertThat(JSON.readTree(get("/api/player-art/2/framing").body()).has(image)).isFalse();
	}

	@Test
	void backgroundSavedBeforeFramingsBecomesTheFramingOfTheCurrentLayout() throws Exception {
		JsonNode framing = JSON.readTree(get("/api/player-art/1/framing").body()).get(LEGACY_IMAGE);

		assertThat(framing.get("4").get("x").asInt()).isEqualTo(30);
		assertThat(framing.get("4").get("y").asInt()).isEqualTo(70);
	}

	// --- Aides

	private String upload() throws Exception {
		HttpResponse<String> created = post("/api/user-images?name=test.png", ImageFormatTest.png(1600, 900), "image/png");
		assertThat(created.statusCode()).isEqualTo(201);
		return JSON.readTree(created.body()).get("id").asString();
	}

	private void setUploadBackground(int player, String imageId, String framingsJson) throws Exception {
		client.send("{\"type\":\"SET_PLAYER_BACKGROUND\",\"player\":" + player + ",\"kind\":\"UPLOAD\",\"imageId\":\""
				+ imageId + "\",\"framings\":" + framingsJson + "}");
	}

	private void startGame(int playerCount) throws Exception {
		String names = String.join(",", java.util.Collections.nCopies(playerCount, "\"\""));
		client.send("{\"type\":\"START_GAME\",\"format\":\"STANDARD\",\"startingLife\":20,\"players\":[" + names + "]}");
	}

	private static int[] position(JsonNode background) {
		return new int[] {background.get("positionX").asInt(), background.get("positionY").asInt()};
	}

	/** Positions X puis Y des joueurs demandés (indices à partir de 0) dans un message STATE. */
	private static int[] positions(JsonNode state, int... playerIndexes) {
		int[] result = new int[playerIndexes.length * 2];
		for (int index = 0; index < playerIndexes.length; index++) {
			int[] position = position(state.get("game").get("players").get(playerIndexes[index]).get("background"));
			result[index * 2] = position[0];
			result[index * 2 + 1] = position[1];
		}
		return result;
	}

	private HttpResponse<String> get(String path) throws Exception {
		try (HttpClient http = HttpClient.newHttpClient()) {
			return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private HttpResponse<byte[]> getBytes(String path) throws Exception {
		try (HttpClient http = HttpClient.newHttpClient()) {
			return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofByteArray());
		}
	}

	private HttpResponse<String> post(String path, byte[] body, String contentType) throws Exception {
		try (HttpClient http = HttpClient.newHttpClient()) {
			HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Content-Type", contentType)
					.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	private HttpResponse<String> delete(String path) throws Exception {
		try (HttpClient http = HttpClient.newHttpClient()) {
			return http.send(HttpRequest.newBuilder(uri(path)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
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

		JsonNode next() throws InterruptedException {
			String payload = messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThat(payload).as("message reçu avant le délai").isNotNull();
			return JSON.readTree(payload);
		}
	}
}
