package com.wahib.mtg.mtg_companion.cards;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
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
 * Seul point d'accès à l'API Scryfall (https://scryfall.com/docs/api), dont il applique les règles :
 * User-Agent et Accept explicites, délai minimal entre deux requêtes, pause de 30 s après un HTTP 429.
 */
@Component
public class ScryfallClient {

	static final String USER_AGENT = "MtgCompanion/0.1 (local Magic life counter)";

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(30);
	// Limites officielles : 500 ms pour /cards/search et /cards/named, 100 ms pour le reste ; on garde une marge
	private static final Duration CARD_SEARCH_INTERVAL = Duration.ofMillis(550);
	private static final Duration GENERAL_INTERVAL = Duration.ofMillis(120);

	private static final String UNREACHABLE_MESSAGE =
			"Scryfall ne répond pas. Vérifiez la connexion Internet du PC qui fait tourner le serveur.";

	private final RestClient restClient;
	private final JsonMapper jsonMapper;
	private final RequestThrottle throttle;
	private final RequestThrottle.Lane cardSearchLane;
	private final RequestThrottle.Lane generalLane;

	@Autowired
	public ScryfallClient(@Value("${mtg.scryfall.base-url}") String baseUrl, JsonMapper jsonMapper) {
		this(baseUrl, jsonMapper, new RequestThrottle());
	}

	ScryfallClient(String baseUrl, JsonMapper jsonMapper, RequestThrottle throttle) {
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
		this.throttle = throttle;
		this.cardSearchLane = throttle.lane(CARD_SEARCH_INTERVAL);
		this.generalLane = throttle.lane(GENERAL_INTERVAL);
	}

	/** Noms anglais complets commençant par (ou proches de) {@code query}. */
	public List<String> autocomplete(String query) {
		return getJson(generalLane, "/cards/autocomplete", Map.of("q", query))
				.map(catalog -> strings(catalog.get("data")))
				.orElse(List.of());
	}

	/** Version anglaise par défaut de la carte portant exactement ce nom anglais. */
	public Optional<JsonNode> findByExactName(String englishName) {
		return getJson(cardSearchLane, "/cards/named", Map.of("exact", englishName));
	}

	/**
	 * Première page (175 cartes au plus) d'une recherche avec la syntaxe Scryfall.
	 *
	 * @param allPrints toutes les impressions de chaque carte, de la plus récente à la plus ancienne,
	 *                  au lieu d'une seule impression par carte
	 */
	public List<JsonNode> search(String query, boolean allPrints) {
		// order=released trie par défaut de la plus récente à la plus ancienne
		Map<String, String> parameters = allPrints
				? Map.of("q", query, "unique", "prints", "order", "released")
				: Map.of("q", query);
		// Scryfall répond 404 quand la recherche ne trouve rien
		return getJson(cardSearchLane, "/cards/search", parameters)
				.map(list -> elements(list.get("data")))
				.orElse(List.of());
	}

	/** Télécharge une image (hébergée sur *.scryfall.io, sans limite de débit). */
	public byte[] downloadImage(String imageUrl) {
		requireNotPaused();
		RawResponse response = execute(restClient.get().uri(URI.create(imageUrl)).accept(MediaType.ALL));
		if (!response.isSuccessful()) {
			throw new ScryfallUnavailableException(
					"Impossible de télécharger l'image de la carte (HTTP " + response.status() + ").");
		}
		return response.body();
	}

	private Optional<JsonNode> getJson(RequestThrottle.Lane lane, String path, Map<String, String> parameters) {
		requireNotPaused();
		waitForTurn(lane);
		RawResponse response = execute(restClient.get()
				.uri(builder -> {
					builder.path(path);
					// Valeurs passées en variables : elles sont encodées entièrement (espaces, guillemets, deux-points…)
					parameters.keySet().forEach(name -> builder.queryParam(name, "{" + name + "}"));
					return builder.build(parameters);
				})
				.accept(MediaType.APPLICATION_JSON));

		if (response.status() == 404) {
			return Optional.empty();
		}
		if (response.status() == 429) {
			throttle.pauseFor(RATE_LIMIT_PAUSE);
			throw new ScryfallUnavailableException(pauseMessage(RATE_LIMIT_PAUSE));
		}
		if (!response.isSuccessful()) {
			throw new ScryfallUnavailableException("Scryfall a renvoyé une erreur (HTTP " + response.status() + ").");
		}
		try {
			return Optional.of(jsonMapper.readTree(new String(response.body(), StandardCharsets.UTF_8)));
		}
		catch (JacksonException e) {
			throw new ScryfallUnavailableException("Réponse de Scryfall illisible.", e);
		}
	}

	private RawResponse execute(RestClient.RequestHeadersSpec<?> request) {
		try {
			return request.exchange((ignoredRequest, response) ->
					new RawResponse(response.getStatusCode().value(), response.getBody().readAllBytes()));
		}
		catch (RestClientException e) {
			throw new ScryfallUnavailableException(UNREACHABLE_MESSAGE, e);
		}
	}

	private void requireNotPaused() {
		Duration remaining = throttle.remainingPause();
		if (!remaining.isZero()) {
			throw new ScryfallUnavailableException(pauseMessage(remaining));
		}
	}

	private void waitForTurn(RequestThrottle.Lane lane) {
		try {
			throttle.acquire(lane);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ScryfallUnavailableException("Requête vers Scryfall interrompue.", e);
		}
	}

	private static String pauseMessage(Duration remaining) {
		long seconds = Math.max(1, (remaining.toMillis() + 999) / 1000);
		return "Scryfall limite temporairement les requêtes : réessayez dans " + seconds + " s.";
	}

	private static List<JsonNode> elements(JsonNode array) {
		List<JsonNode> result = new ArrayList<>();
		if (array != null) {
			for (int index = 0; index < array.size(); index++) {
				result.add(array.get(index));
			}
		}
		return result;
	}

	private static List<String> strings(JsonNode array) {
		return elements(array).stream().map(JsonNode::asString).toList();
	}

	private record RawResponse(int status, byte[] body) {

		boolean isSuccessful() {
			return status >= 200 && status < 300;
		}
	}
}
