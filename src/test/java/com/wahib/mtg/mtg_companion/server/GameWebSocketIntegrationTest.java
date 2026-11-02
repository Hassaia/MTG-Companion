package com.wahib.mtg.mtg_companion.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameWebSocketIntegrationTest {

	private static final long TIMEOUT_SECONDS = 5;

	@TempDir
	static Path stateDirectory;

	@DynamicPropertySource
	static void stateFile(DynamicPropertyRegistry registry) {
		registry.add("mtg.state-file", () -> stateDirectory.resolve("state.json").toString());
		registry.add("mtg.player-art-file", () -> stateDirectory.resolve("player-art.json").toString());
		registry.add("mtg.user-images-dir", () -> stateDirectory.resolve("user-images").toString());
	}

	@Value("${local.server.port}")
	int port;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	GameStateStore store;

	private TestClient first;
	private TestClient second;

	@BeforeEach
	void connectTwoClientsOnFreshGame() throws Exception {
		first = connect();
		second = connect();
		assertThat(first.nextMessage().get("type").asString()).isEqualTo("STATE");
		assertThat(second.nextMessage().get("type").asString()).isEqualTo("STATE");

		first.send("""
				{"type":"START_GAME","format":"STANDARD","startingLife":20,"players":["Alice","Bob"]}""");
		first.nextMessage();
		second.nextMessage();
	}

	@AfterEach
	void disconnect() throws Exception {
		first.session.close();
		second.session.close();
	}

	@Test
	void actionFromOneClientIsBroadcastToTheOther() throws Exception {
		first.send("""
				{"type":"CHANGE_LIFE","player":2,"delta":-3}""");

		JsonNode received = second.nextMessage();
		assertThat(received.get("type").asString()).isEqualTo("STATE");
		assertThat(received.get("serverAddress").asString()).startsWith("http://");
		JsonNode players = received.get("game").get("players");
		assertThat(players.get(0).get("life").asInt()).isEqualTo(20);
		assertThat(players.get(1).get("name").asString()).isEqualTo("Bob");
		assertThat(players.get(1).get("life").asInt()).isEqualTo(17);

		assertThat(store.load().orElseThrow().players().get(1).life()).isEqualTo(17);
	}

	@Test
	void newClientImmediatelyReceivesCurrentState() throws Exception {
		first.send("""
				{"type":"CHANGE_LIFE","player":1,"delta":-5}""");
		first.nextMessage();

		TestClient late = connect();
		JsonNode received = late.nextMessage();
		late.session.close();

		assertThat(received.get("game").get("players").get(0).get("life").asInt()).isEqualTo(15);
	}

	@Test
	void invalidActionIsReportedOnlyToItsSender() throws Exception {
		first.send("""
				{"type":"CHANGE_LIFE","player":7,"delta":-1}""");

		JsonNode error = first.nextMessage();
		assertThat(error.get("type").asString()).isEqualTo("ERROR");
		assertThat(error.get("message").asString()).contains("inexistant");
		assertThat(second.messages.poll(300, TimeUnit.MILLISECONDS)).isNull();
	}

	@Test
	void unreadableMessageIsReportedAsError() throws Exception {
		first.send("{\"type\":\"UNKNOWN\"}");

		assertThat(first.nextMessage().get("type").asString()).isEqualTo("ERROR");
	}

	private TestClient connect() throws Exception {
		TestClient client = new TestClient();
		client.session = new StandardWebSocketClient()
				.execute(client, "ws://localhost:" + port + "/ws")
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return client;
	}

	private class TestClient extends TextWebSocketHandler {

		final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		WebSocketSession session;

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			messages.add(message.getPayload());
		}

		void send(String json) throws Exception {
			session.sendMessage(new TextMessage(json));
		}

		JsonNode nextMessage() throws InterruptedException {
			String payload = messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertThat(payload).as("message reçu avant le délai").isNotNull();
			return jsonMapper.readTree(payload);
		}
	}
}
