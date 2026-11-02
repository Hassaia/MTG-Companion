package com.wahib.mtg.mtg_companion.cards;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wahib.mtg.mtg_companion.cards.CardIllustrations.Art;
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
	private static final Duration MISSING_VARIANT_MEMORY = Duration.ofMinutes(10);

	private static final Logger log = LoggerFactory.getLogger(CardService.class);

	/** @param warning message à afficher au joueur quand les suggestions sont incomplètes, sinon null */
	public record SuggestionResult(List<Suggestion> suggestions, String warning) {
	}

	/** Une impression avec le nom (anglais) de sa carte. */
	public record PrintArt(String cardName, Illustration print) {
	}

	/** @param artist artiste tel que renseigné par Scryfall, ou null */
	public record CachedArt(String cardName, String artist) {
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
	/** Versions linguistiques que Scryfall n'a pas : inutile de les redemander à chaque changement de langue. */
	private final Map<String, Instant> missingVariants = new ConcurrentHashMap<>();

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
		Optional<CardIllustrations> cached = cache.findIllustrations(englishName);
		// Une entrée écrite avant la prise en charge des illustrations seules est récupérée de nouveau
		CardIllustrations illustrations = cached.filter(CardService::hasArtData).orElseGet(() -> {
			try {
				return fetchAndCache(englishName).illustrations();
			}
			catch (ScryfallUnavailableException e) {
				return cached.or(() -> cache.find(englishName).map(CardService::defaultIllustrationOnly)).orElseThrow(() -> e);
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
	 * Carte prête à afficher, images comprises, dans la langue demandée. Les versions FR et EN sont celles
	 * de la même édition et du même numéro de collection que l'impression choisie, jamais une autre impression.
	 * Une langue indisponible est remplacée par l'autre (avec un avertissement) ; en mode {@code both},
	 * seule la langue disponible est affichée.
	 *
	 * @param englishName nom anglais complet, tel que renvoyé par les suggestions
	 * @param printId     impression choisie par le joueur, ou null pour le choix automatique
	 *                    (première illustration, française si possible) ; une impression inconnue donne aussi le choix automatique
	 * @param lang        {@code fr}, {@code en} ou {@code both}
	 * @throws CardNotFoundException       si aucune carte ne porte ce nom
	 * @throws ScryfallUnavailableException si aucune image n'est disponible dans aucune des langues
	 */
	public CardDisplay resolve(String englishName, String printId, String lang) {
		Illustration base = baseIllustration(englishName, printId);
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<CardDisplay.Variant> variants = new ArrayList<>();
		if (lang.equals("both")) {
			for (String wanted : List.of("fr", "en")) {
				Optional<Illustration> found = variant(englishName, base, wanted, errors);
				if (found.isPresent()) {
					variants.add(new CardDisplay.Variant(wanted, found.get().images()));
				}
				else {
					warnings.add("Version " + languageName(wanted) + " indisponible" + reference(base) + " : seule la version "
							+ languageName(other(wanted)) + " est affichée.");
				}
			}
			if (variants.size() == 2 || variants.isEmpty()) {
				warnings.clear();
			}
		}
		else {
			Optional<Illustration> found = variant(englishName, base, lang, errors);
			if (found.isPresent()) {
				variants.add(new CardDisplay.Variant(lang, found.get().images()));
			}
			else {
				found = variant(englishName, base, other(lang), errors);
				found.ifPresent(print -> {
					variants.add(new CardDisplay.Variant(other(lang), print.images()));
					warnings.add("Version " + languageName(lang) + " indisponible" + reference(base) + " : version "
							+ languageName(other(lang)) + " affichée.");
				});
			}
		}
		if (variants.isEmpty()) {
			throw new ScryfallUnavailableException(errors.isEmpty()
					? "Aucune image disponible" + reference(base) + " dans la langue demandée."
					: errors.getFirst());
		}
		if (!errors.isEmpty() && !warnings.isEmpty()) {
			warnings.add(errors.getFirst());
		}
		return new CardDisplay(englishName, variants, warnings);
	}

	/** L'impression choisie, ou à défaut la première illustration de la carte (française si possible). */
	private Illustration baseIllustration(String englishName, String printId) {
		if (printId != null) {
			// Une illustration de la grille, ou une impression demandée par édition et numéro
			Optional<Illustration> chosen = illustrations(englishName).illustrations().stream()
					.filter(illustration -> illustration.printId().equals(printId))
					.findFirst()
					.or(() -> cache.findPrint(englishName, printId))
					.or(() -> fetchPrint(englishName, printId));
			if (chosen.isPresent()) {
				return chosen.get();
			}
			log.info("Impression {} inconnue pour {}, choix automatique", printId, englishName);
		}
		return illustrations(englishName).illustrations().stream()
				.filter(illustration -> !illustration.images().isEmpty())
				.findFirst()
				.or(() -> defaultIllustrationOnly(defaultCard(englishName)).illustrations().stream().findFirst())
				.orElseThrow(() -> new ScryfallUnavailableException("Aucune image disponible pour « " + englishName + " »."));
	}

	/** La version de {@code base} dans cette langue, images téléchargées ; vide si elle n'existe pas ou est inaccessible. */
	private Optional<Illustration> variant(String englishName, Illustration base, String lang, List<String> errors) {
		try {
			Optional<Illustration> found = findVariant(englishName, base, lang);
			if (found.isPresent()) {
				downloadMissingImages(found.get().images());
			}
			return found;
		}
		catch (ScryfallUnavailableException e) {
			errors.add(e.getMessage());
			return Optional.empty();
		}
	}

	private Optional<Illustration> findVariant(String englishName, Illustration base, String lang) {
		if (lang.equalsIgnoreCase(base.lang())) {
			return Optional.of(base);
		}
		Illustration located = withSetCode(englishName, base);
		if (located.setCode() == null || located.collectorNumber() == null) {
			return Optional.empty();
		}
		String key = located.setCode() + "/" + located.collectorNumber() + "/" + lang;
		Optional<Illustration> cached = cache.findVariant(englishName, located.setCode(), located.collectorNumber(), lang);
		if (cached.isPresent()) {
			return cached;
		}
		Instant missingUntil = missingVariants.get(key);
		if (missingUntil != null && Instant.now().isBefore(missingUntil)) {
			return Optional.empty();
		}
		// Correspondance exacte (édition, numéro, langue) ; l'image doit être une vraie photo et la carte la même
		Optional<Illustration> fetched = scryfall.findByCollectorNumber(located.setCode(), located.collectorNumber(), lang)
				.filter(print -> print.get("name") != null && englishName.equalsIgnoreCase(print.get("name").asString()))
				.filter(ScryfallCardMapper::hasRealImage)
				.flatMap(ScryfallCardMapper::illustration);
		if (fetched.isEmpty()) {
			missingVariants.put(key, Instant.now().plus(MISSING_VARIANT_MEMORY));
			return Optional.empty();
		}
		try {
			cache.saveVariant(englishName, located.setCode(), located.collectorNumber(), lang, fetched.get());
		}
		catch (IOException e) {
			log.warn("Impossible de mettre en cache la version {} de {}", lang, englishName, e);
		}
		return fetched;
	}

	/** Une impression en cache avant la prise en charge des langues ne connaît pas son édition : Scryfall la donne. */
	private Illustration withSetCode(String englishName, Illustration print) {
		if (print.setCode() != null) {
			return print;
		}
		Optional<Illustration> fetched = scryfall.findById(print.printId()).flatMap(ScryfallCardMapper::illustration);
		fetched.ifPresent(found -> savePrint(englishName, found));
		return fetched.orElse(print);
	}

	private static String other(String lang) {
		return lang.equals("fr") ? "en" : "fr";
	}

	private static String languageName(String lang) {
		return lang.equals("fr") ? "française" : "anglaise";
	}

	private static String reference(Illustration print) {
		return print.setCode() == null || print.collectorNumber() == null ? ""
				: " (" + print.setCode().toUpperCase() + " n° " + print.collectorNumber() + ")";
	}

	/**
	 * Illustration seule d'une impression, prête à servir : téléchargée dans le cache si elle n'y est pas.
	 *
	 * @param printId impression choisie, ou null pour la première illustration qui en a une
	 * @return vide si l'impression est inconnue ou n'a aucune illustration seule
	 * @throws CardNotFoundException        si aucune carte ne porte ce nom
	 * @throws ScryfallUnavailableException si ce qui manque au cache ne peut pas être récupéré
	 */
	public Optional<PrintArt> artworks(String englishName, String printId) {
		CardIllustrations all = illustrations(englishName);
		List<Illustration> known = all.illustrations();
		Optional<Illustration> print = printId == null
				? known.stream().filter(Illustration::hasArt).findFirst()
				: known.stream().filter(illustration -> illustration.printId().equals(printId)).findFirst()
						// Impression trouvée par édition et numéro, ou enregistrée avant les illustrations seules
						.or(() -> cache.findPrint(englishName, printId).filter(found -> found.arts() != null))
						.or(() -> fetchPrint(englishName, printId));
		print = print.filter(Illustration::hasArt);
		if (print.isEmpty()) {
			return Optional.empty();
		}
		for (Art art : print.get().arts()) {
			String fileName = CardCache.artFileName(print.get().printId(), art.face());
			if (cache.artPath(fileName).isPresent()) {
				continue;
			}
			try {
				cache.saveArt(fileName, scryfall.downloadImage(art.sourceUrl()));
			}
			catch (IOException e) {
				throw new ScryfallUnavailableException("Impossible d'enregistrer l'illustration sur le disque.", e);
			}
		}
		return Optional.of(new PrintArt(all.name(), print.get()));
	}

	/**
	 * Illustration déjà en cache, sans aucun appel réseau : sert à vérifier un choix reçu d'un téléphone.
	 *
	 * @return vide si cette face de cette impression n'est pas en cache
	 */
	public Optional<CachedArt> cachedArt(String englishName, String printId, int face) {
		if (englishName == null || !CardCache.isValidPrintId(printId)) {
			return Optional.empty();
		}
		return cache.findIllustrations(englishName)
				.flatMap(found -> found.illustrations().stream()
						.filter(illustration -> illustration.printId().equals(printId)).findFirst()
						.map(illustration -> new PrintArt(found.name(), illustration)))
				.or(() -> cache.findPrint(englishName, printId).map(print -> new PrintArt(englishName, print)))
				.filter(found -> found.print().arts() != null && cache.artPath(CardCache.artFileName(printId, face)).isPresent())
				.flatMap(found -> found.print().arts().stream().filter(art -> art.face() == face).findFirst()
						.map(art -> new CachedArt(found.cardName(), art.artist())));
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
	private static boolean hasArtData(CardIllustrations illustrations) {
		return illustrations.illustrations().stream().allMatch(illustration -> illustration.arts() != null);
	}

	private static CardIllustrations defaultIllustrationOnly(Card card) {
		if (card.images().isEmpty()) {
			return new CardIllustrations(card.name(), List.of());
		}
		String imageFile = card.images().getFirst().fileName();
		String printId = imageFile.substring(0, imageFile.lastIndexOf('-'));
		return new CardIllustrations(card.name(),
				List.of(new Illustration(printId, card.lang(), null, null, null, null, card.images())));
	}

	private void downloadMissingImages(List<Card.CardImage> images) {
		for (Card.CardImage image : images) {
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
