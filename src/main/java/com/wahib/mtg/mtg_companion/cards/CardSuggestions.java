package com.wahib.mtg.mtg_companion.cards;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Construit la liste de suggestions affichée pendant la frappe, à partir des noms français
 * (recherche Scryfall en {@code lang:fr}) et des noms anglais (autocomplétion Scryfall).
 * La comparaison ignore la casse, les accents et la ponctuation.
 */
public final class CardSuggestions {

	public static final int MAX_SUGGESTIONS = 10;

	private static final int STARTS_WITH = 0;
	private static final int WORD_STARTS_WITH = 1;
	private static final int CONTAINS = 2;
	private static final int NO_MATCH = 3;

	/**
	 * @param name  nom anglais officiel : c'est lui qui est renvoyé au serveur pour afficher la carte
	 * @param label nom à afficher, en français si possible
	 */
	public record Suggestion(String name, String label) {
	}

	/** @param localName nom français, ou null s'il n'est pas connu */
	public record LocalizedName(String name, String localName) {
	}

	private CardSuggestions() {
	}

	/**
	 * Fusionne les résultats français et anglais : les meilleures correspondances d'abord, les noms
	 * français avant les anglais à égalité, sans doublon.
	 */
	public static List<Suggestion> merge(String query, List<LocalizedName> frenchMatches, List<String> englishNames) {
		List<Candidate> candidates = new ArrayList<>();
		for (LocalizedName match : frenchMatches) {
			candidates.add(Candidate.of(query, match, true));
		}
		for (String englishName : englishNames) {
			candidates.add(Candidate.of(query, new LocalizedName(englishName, null), false));
		}
		return rank(candidates);
	}

	/** Suggestions hors connexion, parmi les cartes déjà connues : seules celles qui correspondent sont gardées. */
	public static List<Suggestion> filterKnown(String query, List<LocalizedName> knownCards) {
		List<Candidate> candidates = knownCards.stream()
				.map(card -> Candidate.of(query, card, true))
				.filter(candidate -> candidate.score < NO_MATCH)
				.toList();
		return rank(candidates);
	}

	static String normalize(String text) {
		String withoutAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return withoutAccents.toLowerCase(Locale.ROOT)
				.replaceAll("[^\\p{L}\\p{N}]+", " ")
				.strip();
	}

	private static List<Suggestion> rank(List<Candidate> candidates) {
		Collator frenchOrder = Collator.getInstance(Locale.FRENCH);
		List<Candidate> sorted = candidates.stream()
				.sorted(Comparator.comparingInt((Candidate candidate) -> candidate.score)
						.thenComparing(candidate -> !candidate.french)
						.thenComparing(candidate -> candidate.suggestion.label(), frenchOrder))
				.toList();
		// Une même carte peut venir des deux sources : on garde sa meilleure entrée
		Map<String, Suggestion> unique = new LinkedHashMap<>();
		for (Candidate candidate : sorted) {
			unique.putIfAbsent(candidate.suggestion.name().toLowerCase(Locale.ROOT), candidate.suggestion);
		}
		return unique.values().stream().limit(MAX_SUGGESTIONS).toList();
	}

	private static int matchScore(String normalizedQuery, String text) {
		if (text == null || normalizedQuery.isEmpty()) {
			return NO_MATCH;
		}
		String normalizedText = normalize(text);
		if (normalizedText.startsWith(normalizedQuery)) {
			return STARTS_WITH;
		}
		if ((" " + normalizedText).contains(" " + normalizedQuery)) {
			return WORD_STARTS_WITH;
		}
		return normalizedText.contains(normalizedQuery) ? CONTAINS : NO_MATCH;
	}

	private record Candidate(Suggestion suggestion, int score, boolean french) {

		static Candidate of(String query, LocalizedName card, boolean french) {
			String normalizedQuery = normalize(query);
			boolean hasTranslation = card.localName() != null && !card.localName().equalsIgnoreCase(card.name());
			String label = hasTranslation ? card.localName() : card.name();
			int score = Math.min(matchScore(normalizedQuery, card.localName()), matchScore(normalizedQuery, card.name()));
			return new Candidate(new Suggestion(card.name(), label), score, french && hasTranslation);
		}
	}
}
