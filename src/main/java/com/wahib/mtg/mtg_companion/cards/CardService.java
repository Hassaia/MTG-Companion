package com.wahib.mtg.mtg_companion.cards;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wahib.mtg.mtg_companion.cards.CardSuggestions.LocalizedName;
import com.wahib.mtg.mtg_companion.cards.CardSuggestions.Suggestion;

import tools.jackson.databind.JsonNode;

/**
 * Recherche de cartes : suggestions pendant la frappe et résolution d'une carte à afficher.
 * Le cache sur disque est toujours consulté avant Scryfall.
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
			List<LocalizedName> frenchMatches = scryfall.search(query + " lang:fr", false).stream()
					.limit(MAX_FRENCH_RESULTS)
					.map(ScryfallCardMapper::localizedName)
					.toList();
			List<String> englishNames = scryfall.autocomplete(query);
			List<Suggestion> suggestions = CardSuggestions.merge(query, frenchMatches, englishNames);
			remember(memoryKey, suggestions);
			return new SuggestionResult(suggestions, null);
		}
		catch (ScryfallUnavailableException e) {
			List<Suggestion> known = CardSuggestions.filterKnown(query, knownCards());
			return new SuggestionResult(known, e.getMessage() + " Seules les cartes déjà affichées sont proposées.");
		}
	}

	/**
	 * Carte prête à afficher, images comprises : depuis le cache si possible, sinon depuis Scryfall
	 * (puis mise en cache).
	 *
	 * @param englishName nom anglais complet, tel que renvoyé par les suggestions
	 * @throws CardNotFoundException       si aucune carte ne porte ce nom
	 * @throws ScryfallUnavailableException si la carte n'est pas en cache et que Scryfall est injoignable
	 */
	public Card resolve(String englishName) {
		Optional<Card> cached = cache.find(englishName);
		if (cached.isPresent() && cached.get().images().stream().allMatch(image -> cache.hasImage(image.fileName()))) {
			return cached.get();
		}
		Card card = cached.orElseGet(() -> fetchFromScryfall(englishName));
		downloadMissingImages(card);
		try {
			cache.save(card);
		}
		catch (IOException e) {
			log.warn("Impossible de mettre en cache la carte {}", card.name(), e);
		}
		return card;
	}

	private Card fetchFromScryfall(String englishName) {
		JsonNode englishCard = scryfall.findByExactName(englishName)
				.orElseThrow(() -> new CardNotFoundException(englishName));
		JsonNode oracleId = englishCard.get("oracle_id");
		// Les rares cartes « réversibles » n'ont pas d'oracle_id global : on se contente alors de l'anglais
		List<JsonNode> frenchPrints = oracleId == null
				? List.of()
				: scryfall.search("oracleid:" + oracleId.asString() + " lang:fr", true);
		return ScryfallCardMapper.toCard(englishCard, frenchPrints);
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
