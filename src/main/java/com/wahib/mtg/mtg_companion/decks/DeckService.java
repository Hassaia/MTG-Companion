package com.wahib.mtg.mtg_companion.decks;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wahib.mtg.mtg_companion.cards.ScryfallClient;
import com.wahib.mtg.mtg_companion.cards.ScryfallUnavailableException;
import com.wahib.mtg.mtg_companion.decks.DeckContent.DeckCard;

import tools.jackson.databind.JsonNode;

/**
 * Catalogue des préconstruits Commander et contenu des decks. Le catalogue est rafraîchi une fois par
 * semaine ; si MTGJSON est injoignable, le cache (même périmé) est réutilisé. Un deck n'est téléchargé
 * qu'à sa première consultation, puis relu depuis le disque.
 */
@Service
public class DeckService {

	static final Duration CATALOG_LIFETIME = Duration.ofDays(7);
	/** Après un échec de rafraîchissement, on réutilise le cache sans réessayer pendant ce délai. */
	static final Duration RETRY_DELAY = Duration.ofMinutes(10);
	static final int MAX_RESULTS = 60;

	private static final Logger log = LoggerFactory.getLogger(DeckService.class);
	/** Seuls identifiants acceptés : empêche toute lecture hors du dossier du cache et toute URL détournée. */
	private static final Pattern DECK_ID = Pattern.compile("[A-Za-z0-9_]{1,150}");

	private final MtgJsonClient mtgJson;
	private final ScryfallClient scryfall;
	private final DeckCache cache;

	private DeckCache.CachedCatalog catalog;
	private Map<String, String> setNames = Map.of();
	private boolean setNamesLoaded;
	private Instant lastFailure = Instant.MIN;
	private final Map<String, List<String>> commanders = new ConcurrentHashMap<>();

	public DeckService(MtgJsonClient mtgJson, ScryfallClient scryfall, DeckCache cache) {
		this.mtgJson = mtgJson;
		this.scryfall = scryfall;
		this.cache = cache;
		this.commanders.putAll(cache.commanders());
	}

	public static boolean isValidId(String id) {
		return id != null && DECK_ID.matcher(id).matches();
	}

	/**
	 * Decks correspondant à la recherche (nom, édition, code ou commandant connu), du plus récent au plus ancien ;
	 * {@value #MAX_RESULTS} au plus.
	 *
	 * @throws DecksUnavailableException si le catalogue n'est ni en cache ni accessible
	 */
	public List<DeckSummary> search(String query) {
		List<DeckSummary> found = DeckCatalog.search(entries(), query, setNames(), commanders);
		return found.size() > MAX_RESULTS ? found.subList(0, MAX_RESULTS) : found;
	}

	public Optional<DeckSummary> find(String id) {
		return entries().stream()
				.filter(entry -> entry.fileName().equals(id))
				.findFirst()
				.map(entry -> DeckCatalog.summary(entry, setNames(), commanders));
	}

	/** Commandants déjà connus d'un deck, sans rien télécharger. */
	public List<String> knownCommanders(String id) {
		return commanders.getOrDefault(id, List.of());
	}

	/**
	 * Cartes d'un deck du catalogue, téléchargées si elles ne sont pas en cache.
	 *
	 * @throws DeckNotFoundException     si le deck n'est pas dans le catalogue
	 * @throws DecksUnavailableException si le deck n'est pas en cache et que MTGJSON est injoignable
	 */
	public DeckContent deck(String id) {
		if (!isValidId(id) || find(id).isEmpty()) {
			throw new DeckNotFoundException(id);
		}
		Optional<DeckContent> cached = cache.deck(id);
		if (cached.isPresent()) {
			return cached.get();
		}
		JsonNode file = mtgJson.deck(id).orElseThrow(() -> new DeckNotFoundException(id));
		DeckContent deck = parse(id, file.get("data"));
		try {
			cache.saveDeck(deck);
		}
		catch (IOException e) {
			log.warn("Impossible de mettre le deck {} en cache", id, e);
		}
		rememberCommanders(deck);
		return deck;
	}

	/** Enregistre les commandants d'un deck dans l'index ; c'est aussi le point d'entrée d'un futur indexeur. */
	void rememberCommanders(DeckContent deck) {
		commanders.put(deck.id(), deck.commanders().stream().map(DeckCard::name).toList());
		try {
			cache.saveCommanders(new LinkedHashMap<>(commanders));
		}
		catch (IOException e) {
			log.warn("Impossible d'enregistrer l'index des commandants", e);
		}
	}

	/** Réduit un fichier MTGJSON à ce qui sert : commandants et cartes du deck, sans jetons ni doublons d'impression. */
	static DeckContent parse(String id, JsonNode data) {
		if (data == null) {
			throw new DecksUnavailableException("Deck MTGJSON illisible : " + id);
		}
		List<DeckCard> commanderCards = cards(data.get("commander"));
		List<DeckCard> deckCards = cards(data.get("mainBoard")).stream()
				.sorted(Comparator.comparing(DeckCard::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
		return new DeckContent(id, text(data, "name"), text(data, "code"), text(data, "releaseDate"),
				commanderCards, deckCards);
	}

	/** Une ligne par impression : deux lignes d'une même impression voient leurs quantités additionnées. */
	private static List<DeckCard> cards(JsonNode array) {
		Map<String, DeckCard> byPrint = new LinkedHashMap<>();
		if (array != null) {
			for (JsonNode node : array) {
				String name = text(node, "name");
				if (name == null) {
					continue;
				}
				JsonNode identifiers = node.get("identifiers");
				String printId = identifiers == null ? null : text(identifiers, "scryfallId");
				String setCode = text(node, "setCode");
				DeckCard card = new DeckCard(name, node.has("count") ? node.get("count").asInt(1) : 1, printId,
						setCode == null ? null : setCode.toLowerCase(Locale.ROOT), text(node, "number"));
				String key = printId != null ? printId : name + "|" + setCode + "|" + card.number();
				byPrint.merge(key, card, (first, second) ->
						new DeckCard(first.name(), first.count() + second.count(), first.printId(), first.setCode(), first.number()));
			}
		}
		return new ArrayList<>(byPrint.values());
	}

	private synchronized List<DeckCatalog.Entry> entries() {
		boolean fresh = catalog != null && Duration.between(catalog.fetchedAt(), Instant.now()).compareTo(CATALOG_LIFETIME) < 0;
		if (!fresh) {
			if (catalog == null) {
				catalog = cache.catalog().orElse(null);
				fresh = catalog != null && Duration.between(catalog.fetchedAt(), Instant.now()).compareTo(CATALOG_LIFETIME) < 0;
			}
			if (!fresh && Duration.between(lastFailure, Instant.now()).compareTo(RETRY_DELAY) >= 0) {
				refreshCatalog();
			}
		}
		if (catalog == null) {
			throw new DecksUnavailableException("Catalogue des decks indisponible : MTGJSON ne répond pas et rien n'est en cache.");
		}
		return catalog.entries();
	}

	private void refreshCatalog() {
		try {
			JsonNode data = mtgJson.deckList().get("data");
			if (data == null) {
				throw new DecksUnavailableException("Catalogue MTGJSON illisible.");
			}
			List<DeckCatalog.Entry> entries = DeckCatalog.newestFirst(
					DeckCatalog.withoutCollectorsEditions(DeckCatalog.commanderDecks(data)));
			catalog = new DeckCache.CachedCatalog(Instant.now(), entries);
			cache.saveCatalog(catalog);
			loadSetNames(true);
		}
		catch (DecksUnavailableException e) {
			lastFailure = Instant.now();
			log.warn("Catalogue des decks non rafraîchi, le cache est conservé : {}", e.getMessage());
		}
		catch (IOException e) {
			log.warn("Impossible de mettre le catalogue des decks en cache", e);
		}
	}

	private synchronized Map<String, String> setNames() {
		if (!setNamesLoaded) {
			loadSetNames(false);
		}
		return setNames;
	}

	/** Noms d'édition (Scryfall, une requête) ; leur absence ne gêne que la recherche par nom d'édition. */
	private void loadSetNames(boolean refresh) {
		setNamesLoaded = true;
		Optional<DeckCache.CachedSetNames> cached = cache.setNames();
		cached.ifPresent(names -> setNames = names.names());
		boolean stale = cached.isEmpty() || refresh;
		if (!stale) {
			return;
		}
		try {
			Map<String, String> names = scryfall.setNames();
			if (!names.isEmpty()) {
				setNames = names;
				cache.saveSetNames(new DeckCache.CachedSetNames(Instant.now(), names));
			}
		}
		catch (ScryfallUnavailableException | IOException e) {
			log.warn("Noms d'édition indisponibles : {}", e.getMessage());
		}
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}
}
