package com.wahib.mtg.mtg_companion.cards;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Faux Scryfall pour les tests, basé sur le serveur HTTP intégré au JDK : répond avec les fixtures
 * enregistrées et note chaque requête reçue. Les adresses d'images des fixtures sont réécrites
 * pour pointer vers lui.
 */
public final class FakeScryfallServer implements AutoCloseable {

	public static final byte[] IMAGE_BYTES = "fausse image jpeg".getBytes(StandardCharsets.UTF_8);
	private static final String REAL_IMAGE_HOST = "https://cards.scryfall.io";

	public record ReceivedRequest(String path, Map<String, String> parameters, String userAgent, String accept, long nanoTime) {
	}

	private record Stub(String path, Map<String, String> parameters, int status, String body) {
	}

	private final HttpServer server;
	private final List<Stub> stubs = new CopyOnWriteArrayList<>();
	private final List<ReceivedRequest> requests = new CopyOnWriteArrayList<>();

	public FakeScryfallServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	public String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	/** Répond {@code status} avec la fixture donnée quand le chemin et tous les paramètres indiqués correspondent. */
	public FakeScryfallServer stub(String path, Map<String, String> parameters, int status, String fixtureName) {
		String body = ScryfallFixtures.text(fixtureName).replace(REAL_IMAGE_HOST, baseUrl() + "/images");
		stubs.add(new Stub(path, parameters, status, body));
		return this;
	}

	FakeScryfallServer stubStatus(String path, int status) {
		return stubStatus(path, Map.of(), status);
	}

	FakeScryfallServer stubStatus(String path, Map<String, String> parameters, int status) {
		stubs.add(new Stub(path, parameters, status, "{\"object\":\"error\",\"status\":" + status + "}"));
		return this;
	}

	public FakeScryfallServer clearStubs() {
		stubs.clear();
		return this;
	}

	public FakeScryfallServer stubBody(String path, Map<String, String> parameters, String body) {
		stubs.add(new Stub(path, parameters, 200, body));
		return this;
	}

	long countRequests(String path, String parameter, String value) {
		return requests.stream()
				.filter(request -> request.path().equals(path) && value.equals(request.parameters().get(parameter)))
				.count();
	}

	public List<ReceivedRequest> requests() {
		return List.copyOf(requests);
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		Map<String, String> parameters = parseQuery(exchange.getRequestURI().getRawQuery());
		requests.add(new ReceivedRequest(path, parameters,
				exchange.getRequestHeaders().getFirst("User-Agent"),
				exchange.getRequestHeaders().getFirst("Accept"),
				System.nanoTime()));

		if (path.startsWith("/images/")) {
			respond(exchange, 200, "image/jpeg", IMAGE_BYTES);
			return;
		}
		for (Stub stub : stubs) {
			if (stub.path.equals(path) && parameters.entrySet().containsAll(stub.parameters.entrySet())) {
				respond(exchange, stub.status, "application/json", stub.body.getBytes(StandardCharsets.UTF_8));
				return;
			}
		}
		respond(exchange, 404, "application/json",
				ScryfallFixtures.text("named-not-found.json").getBytes(StandardCharsets.UTF_8));
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(body);
		}
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		Map<String, String> parameters = new LinkedHashMap<>();
		if (rawQuery == null) {
			return parameters;
		}
		for (String pair : rawQuery.split("&")) {
			int separator = pair.indexOf('=');
			String name = separator < 0 ? pair : pair.substring(0, separator);
			String value = separator < 0 ? "" : pair.substring(separator + 1);
			parameters.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
		}
		return parameters;
	}
}
