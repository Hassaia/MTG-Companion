package com.wahib.mtg.mtg_companion.decks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Faux MTGJSON pour les tests : sert les fixtures de {@code src/test/resources/mtgjson} (catalogue et decks)
 * et note chaque requête. {@link #setOffline} le fait répondre 503, comme un MTGJSON indisponible.
 */
final class FakeMtgJsonServer implements AutoCloseable {

	private final HttpServer server;
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private volatile boolean offline;

	FakeMtgJsonServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	void setOffline(boolean offline) {
		this.offline = offline;
	}

	List<String> requests() {
		return List.copyOf(requests);
	}

	long countRequests(String path) {
		return requests.stream().filter(path::equals).count();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		requests.add(path);
		if (offline) {
			respond(exchange, 503, "{}");
			return;
		}
		String resource = path.equals("/DeckList.json") ? "/mtgjson/DeckList.json" : "/mtgjson" + path;
		try (InputStream input = FakeMtgJsonServer.class.getResourceAsStream(resource)) {
			if (input == null || !path.endsWith(".json")) {
				respond(exchange, 404, "{}");
			}
			else {
				respond(exchange, 200, new String(input.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] content = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, content.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(content);
		}
	}
}
