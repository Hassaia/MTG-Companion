package com.wahib.mtg.mtg_companion.cards;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wahib.mtg.mtg_companion.cards.CardIllustrations.Illustration;
import com.wahib.mtg.mtg_companion.cards.CardSuggestions.LocalizedName;
import com.wahib.mtg.mtg_companion.cards.CardSuggestions.Suggestion;
import com.wahib.mtg.mtg_companion.cards.ScryfallClient.SearchMode;

import tools.jackson.databind.JsonNode;

/**
 * Recherche de cartes : suggestions pendant la frappe, illustrations d'une carte, et résolution
 * d'une carte à afficher. Le cache sur disque est toujours consulté avant Scryfall.
 */
@Service
public class CardService {

	public static final int MIN_QUERY_LENGTH = 2;
	private static final int MAX_FRENCH_RESULTS = 20;
	private static final int MAX_REMEMBERED_QUERIES = 200;
	private static final Duration SUGGESTIONS_MEMORY = Duration.ofMinutes(10);

	private static final Logger log = LoggerFactory.getLogger(CardService.class);

	/** @param warning message à afficher au joueur quand les suggestions sont incomplètes, sinon null */
	public record SuggestionResult(List<Suggestion> suggestions, String warning) {
	}

	private record RememberedSuggestions(List<Suggestion> suggestions, Instant expiresAt) {
	}

	/** Tout ce qu'un premier contact avec une carte récupère chez Scryfall. */
	private record FetchedCard(Card card, CardIllustrations illustrations) {
	}

	private final ScryfallClient scryfall;
	private final CardCache cache;
	/** Suggestions récentes en mémoire : retaper le même texte ne rappelle pas Scryfall. */
	private final Map<String, RememberedSuggestions> recentSuggestions =
			new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, RememberedSuggestions> eldest) {
					return size() > MAX_REMEMBERED_QUERIES;
				}
			};
	/** Adresse Scryfall de chaque vignette proposée, pour la télécharger quand le téléphone la demande. */
	private final Map<String, String> thumbnailUrls = new ConcurrentHashMap<>();

	public CardService(ScryfallClient scryfall, CardCache cache) {
		this.scryfall = scryfall;
		this.cache = cache;
	}

	public SuggestionResult suggest(String rawQuery) {
		String query = toPlainWords(rawQuery);
		if (query.length() < MIN_QUERY_LENGTH) {
			return new SuggestionResult(List.of(), null);
		}
		String memoryKey = CardSuggestions.normalize(query);
		Optional<List<Suggestion>> remembered = remembered(memoryKey);
		if (remembered.isPresent()) {
			return new SuggestionResult(remembered.get(), null);
		}
		try {
			// « FIC 168 » désigne une impression précise ; le texte peut aussi être un nom, on cherche donc les deux
			Optional<Suggestion> exactPrint = CollectorNumberQuery.parse(rawQuery).flatMap(this::findByCollectorNumber);
			List<LocalizedName> frenchMatches = scryfall.search(query + " lang:fr", SearchMode.ONE_PER_CARD).stream()
					.limit(MAX_FRENCH_RESULTS)
					.map(ScryfallCardMapper::localizedName)
					.toList();
			List<String> englishNames = scryfall.autocomplete(query);
			List<Suggestion> byName = CardSuggestions.merge(query, frenchMatches, englishNames);
			List<Suggestion> suggestions = exactPrint
					.map(print -> Stream.concat(Stream.of(print),
								byName.stream().filter(other -> !other.name().equalsIgnoreCase(print.name())))
							.limit(CardSuggestions.MAX_SUGGESTIONS)
							.toList())
					.orElse(byName);
			remember(memoryKey, suggestions);
			return new SuggestionResult(suggestions, null);
		}
		catch (ScryfallUnavailableException e) {
			List<Suggestion> known = CardSuggestions.filterKnown(query, knownCards());
			return new SuggestionResult(known, e.getMessage() + " Seules les cartes déjà affichées sont proposées.");
		}
	}

	/**
	 * Illustrations distinctes de la carte, depuis le cache ou Scryfall. Sans connexion, une carte
	 * déjà affichée propose au moins son illustration par défaut.
	 *
	 * @throws CardNotFoundException       si aucune carte ne porte ce nom
	 * @throws ScryfallUnavailableException si la carte est inconnue du cache et que Scryfall est injoignable
	 */
	public CardIllustrations illustrations(String englishName) {
		CardIllustrations illustrations = cache.findIllustrations(englishName).orElseGet(() -> {
			try {
				return fetchAndCache(englishName).illustrations();
			}
			catch (ScryfallUnavailableException e) {
				return cache.find(englishName).map(CardService::defaultIllustrationOnly).orElseThrow(() -> e);
			}
		});
		illustrations.illustrations().forEach(illustration -> {
			if (illustration.thumbnailUrl() != null) {
				thumbnailUrls.put(illustration.printId(), illustration.thumbnailUrl());
			}
		});
		return illustrations;
	}

	/**
	 * Carte prête à afficher, images comprises.
	 *
	 * @param englishName nom anglais complet, tel que renvoyé par les suggestions
	 * @param printId     impression choisie par le joueur, ou null pour le choix automatique
	 *                    (impression française la plus récente) ; une impression inconnue donne aussi le choix automatique
	 * @throws CardNotFoundException       si aucune carte ne porte ce nom
	 * @throws ScryfallUnavailableException si ce qui manque au cache ne peut pas être récupéré
	 */
	public Card resolve(String englishName, String printId) {
		Card card = defaultCard(englishName);
		if (printId != null) {
			// Une illustration de la grille, ou une impression demandée par édition et numéro
			Optional<Illustration> chosen = illustrations(englishName).illustrations().stream()
					.filter(illustration -> illustration.printId().equals(printId))
					.findFirst()
					.or(() -> cache.findPrint(englishName, printId))
					.or(() -> fetchPrint(englishName, printId));
			if (chosen.isPresent()) {
				// Les noms et textes restent ceux de la carte ; seule l'image change
				card = new Card(card.name(), card.lang(), card.faces(), chosen.get().images());
			}
			else {
				log.info("Impression {} inconnue pour {}, choix automatique", printId, englishName);
			}
		}
		downloadMissingImages(card);
		return card;
	}

	/** Vignette d'une impression proposée au téléphone, téléchargée et mise en cache au premier affichage. */
	public Optional<Path> thumbnail(String printId) {
		Optional<Path> cached = cache.thumbnailPath(printId);
		if (cached.isPresent()) {
			return cached;
		}
		String url = thumbnailUrls.get(printId);
		if (url == null) {
			return Optional.empty();
		}
		byte[] content = scryfall.downloadImage(url);
		try {
			cache.saveThumbnail(printId, content);
		}
		catch (IOException e) {
			throw new ScryfallUnavailableException("Impossible d'enregistrer la vignette sur le disque.", e);
		}
		return cache.thumbnailPath(printId);
	}

	/**
	 * Impression désignée par son édition et son numéro : la française si elle existe avec une vraie
	 * image, sinon l'anglaise. Elle est mise en cache tout de suite, pour un envoi sans nouvel appel.
	 */
	private Optional<Suggestion> findByCollectorNumber(CollectorNumberQuery query) {
		Optional<JsonNode> french = scryfall.findByCollectorNumber(query.setCode(), query.collectorNumber(), "fr");
		Optional<JsonNode> chosen = french.filter(ScryfallCardMapper::hasRealImage)
				.or(() -> scryfall.findByCollectorNumber(query.setCode(), query.collectorNumber(), "en"))
				.or(() -> french);
		if (chosen.isEmpty()) {
			return Optional.empty();
		}
		// Le nom vient de la version française si elle existe, même quand son image est provisoire
		LocalizedName names = ScryfallCardMapper.localizedName(french.orElse(chosen.get()));
		return ScryfallCardMapper.illustration(chosen.get()).map(print -> {
			savePrint(names.name(), print);
			boolean translated = names.localName() != null && !names.localName().equalsIgnoreCase(names.name());
			return new Suggestion(names.name(), translated ? names.localName() : names.name(),
					print.printId(), print.setName() + " · n° " + print.collectorNumber());
		});
	}

	/** Impression inconnue du cache (favori enregistré ailleurs, par exemple) : demandée à Scryfall par son identifiant. */
	private Optional<Illustration> fetchPrint(String englishName, String printId) {
		return scryfall.findById(printId)
				// L'identifiant doit bien désigner une impression de cette carte
				.filter(print -> print.get("name") != null && englishName.equalsIgnoreCase(print.get("name").asString()))
				.flatMap(ScryfallCardMapper::illustration)
				.map(print -> {
					savePrint(englishName, print);
					return print;
				});
	}

	private void savePrint(String cardName, Illustration print) {
		try {
			cache.savePrint(cardName, print);
		}
		catch (IOException e) {
			log.warn("Impossible de mettre en cache l'impression {}", print.printId(), e);
		}
	}

	private Card defaultCard(String englishName) {
		return cache.find(englishName).orElseGet(() -> fetchAndCache(englishName).card());
	}

	/**
	 * Trois requêtes : la carte anglaise (pour son identifiant Oracle), ses impressions françaises et
	 * une impression par illustration. Les deux résultats sont mis en cache ensemble.
	 */
	private FetchedCard fetchAndCache(String englishName) {
		JsonNode englishCard = scryfall.findByExactName(englishName)
				.orElseThrow(() -> new CardNotFoundException(englishName));
		JsonNode oracleId = englishCard.get("oracle_id");
		// Les rares cartes « réversibles » n'ont pas d'oracle_id global : on se contente alors de l'anglais
		List<JsonNode> frenchPrints = oracleId == null ? List.of()
				: scryfall.search("oracleid:" + oracleId.asString() + " lang:fr", SearchMode.ALL_PRINTS);
		List<JsonNode> artPrints = oracleId == null ? List.of()
				: scryfall.search("oracleid:" + oracleId.asString(), SearchMode.ONE_PER_ILLUSTRATION);

		Card card = ScryfallCardMapper.toCard(englishCard, frenchPrints);
		CardIllustrations illustrations = new CardIllustrations(card.name(),
				ScryfallCardMapper.illustrations(englishCard, artPrints, frenchPrints));
		try {
			cache.save(card);
			cache.saveIllustrations(illustrations);
		}
		catch (IOException e) {
			log.warn("Impossible de mettre en cache la carte {}", card.name(), e);
		}
		return new FetchedCard(card, illustrations);
	}

	/** Liste de secours, sans connexion : l'illustration de la carte en cache, dont l'impression est dans le nom d'image. */
	private static CardIllustrations defaultIllustrationOnly(Card card) {
		if (card.images().isEmpty()) {
			return new CardIllustrations(card.name(), List.of());
		}
		String imageFile = card.images().getFirst().fileName();
		String printId = imageFile.substring(0, imageFile.lastIndexOf('-'));
		return new CardIllustrations(card.name(),
				List.of(new Illustration(printId, card.lang(), null, null, null, null, card.images())));
	}

	private void downloadMissingImages(Card card) {
		for (Card.CardImage image : card.images()) {
			if (cache.hasImage(image.fileName())) {
				continue;
			}
			byte[] content = scryfall.downloadImage(image.sourceUrl());
			try {
				cache.saveImage(image.fileName(), content);
			}
			catch (IOException e) {
				throw new ScryfallUnavailableException("Impossible d'enregistrer l'image de la carte sur le disque.", e);
			}
		}
	}

	private List<LocalizedName> knownCards() {
		return cache.all().stream()
				.map(card -> new LocalizedName(card.name(),
						String.join(" // ", card.faces().stream().map(Card.CardFace::name).toList())))
				.toList();
	}

	/** Le texte saisi est passé à la recherche Scryfall : on n'en garde que les mots, sans opérateurs de syntaxe. */
	private static String toPlainWords(String rawQuery) {
		if (rawQuery == null) {
			return "";
		}
		return rawQuery.replaceAll("[^\\p{L}\\p{N}'’\\- ]+", " ").replaceAll("\\s+", " ").strip();
	}

	private synchronized Optional<List<Suggestion>> remembered(String key) {
		RememberedSuggestions entry = recentSuggestions.get(key);
		if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
			recentSuggestions.remove(key);
			return Optional.empty();
		}
		return Optional.of(entry.suggestions());
	}

	private synchronized void remember(String key, List<Suggestion> suggestions) {
		recentSuggestions.put(key, new RememberedSuggestions(suggestions, Instant.now().plus(SUGGESTIONS_MEMORY)));
	}
}
