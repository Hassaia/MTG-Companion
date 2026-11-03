package com.wahib.mtg.mtg_companion.decks;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seul point d'accès à MTGJSON (https://mtgjson.com/api/v5). Seuls le catalogue des decks et un deck
 * à la fois sont téléchargés ; jamais AllPrintings ni AllDeckFiles.
 */
@Component
public class MtgJsonClient {

	static final String USER_AGENT = "MtgCompanion/0.1 (local Magic life counter)";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	// Un deck pèse environ 600 Ko (traductions et rulings compris)
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
	private static final String UNREACHABLE_MESSAGE =
			"MTGJSON ne répond pas. Vérifiez la connexion Internet du PC qui fait tourner le serveur.";

	private final RestClient restClient;
	private final JsonMapper jsonMapper;

	public MtgJsonClient(@Value("${mtg.mtgjson.base-url}") String baseUrl, JsonMapper jsonMapper) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		this.restClient = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
				.build();
		this.jsonMapper = jsonMapper;
	}

	/** Le catalogue complet (tous les types de produits). */
	public JsonNode deckList() {
		return get("DeckList.json").orElseThrow(() -> new DecksUnavailableException("Catalogue MTGJSON introuvable."));
	}

	/** Un deck, par son {@code fileName} ; vide si MTGJSON ne le connaît pas (404). */
	public Optional<JsonNode> deck(String fileName) {
		return get("decks", fileName + ".json");
	}

	private Optional<JsonNode> get(String... pathSegments) {
		try {
			int[] status = new int[1];
			byte[] body = restClient.get()
					.uri(builder -> builder.pathSegment(pathSegments).build())
					.accept(MediaType.APPLICATION_JSON)
					.exchange((request, response) -> {
						status[0] = response.getStatusCode().value();
						return response.getBody().readAllBytes();
					});
			if (status[0] == 404) {
				return Optional.empty();
			}
			if (status[0] < 200 || status[0] >= 300) {
				throw new DecksUnavailableException("MTGJSON a renvoyé une erreur (HTTP " + status[0] + ").");
			}
			return Optional.of(jsonMapper.readTree(new String(body, StandardCharsets.UTF_8)));
		}
		catch (RestClientException e) {
			throw new DecksUnavailableException(UNREACHABLE_MESSAGE, e);
		}
		catch (JacksonException e) {
			throw new DecksUnavailableException("Réponse de MTGJSON illisible.", e);
		}
	}
}
