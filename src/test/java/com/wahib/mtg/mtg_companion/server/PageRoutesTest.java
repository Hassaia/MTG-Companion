package com.wahib.mtg.mtg_companion.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "mtg.state-file=target/test-state/page-routes.json")
class PageRoutesTest {

	@Value("${local.server.port}")
	int port;

	@ParameterizedTest
	@CsvSource({
			"/, MTG Companion",
			"/config, Lancer la partie",
			"/table, card-overlay",
			"/joueur, PV de commandant restants",
			"/js/connection.js, connectToGame",
			"/js/card-search.js, initCardSearch",
			"/js/card-history.js, toggleFavorite",
	})
	void servesPage(String path, String expectedContent) throws Exception {
		HttpResponse<String> response = get(path);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains(expectedContent);
	}

	@ParameterizedTest
	@CsvSource({"/table", "/js/table.js"})
	void browsersMustRevalidateStaticFiles(String path) throws Exception {
		assertThat(get(path).headers().firstValue("Cache-Control")).hasValueSatisfying(
				value -> assertThat(value).contains("no-cache"));
	}

	private HttpResponse<String> get(String path) throws Exception {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
					HttpResponse.BodyHandlers.ofString());
		}
	}
}
