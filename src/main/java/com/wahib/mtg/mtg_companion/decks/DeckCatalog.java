package com.wahib.mtg.mtg_companion.decks;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Règles du catalogue : ne garder que les préconstruits Commander officiels, masquer les Collector's
 * Editions doublons, trier du plus récent au plus ancien et chercher. Aucune entrée/sortie ici.
 */
final class DeckCatalog {

	/** Seul type MTGJSON proposé : les autres produits (Jumpstart, Theme Deck, MTGO Commander Deck…) sont exclus. */
	static final String COMMANDER_DECK_TYPE = "Commander Deck";

	private static final Pattern COLLECTORS_EDITION = Pattern.compile("(?i)\\s*Collector['’]s Edition");
	private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
	private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

	/** Ligne conservée du catalogue MTGJSON ({@code fileName} = identifiant du deck). */
	record Entry(String code, String fileName, String name, String releaseDate) {
	}

	private DeckCatalog() {
	}

	/** Les préconstruits Commander de {@code DeckList.json} (tableau {@code data}). */
	static List<Entry> commanderDecks(JsonNode data) {
		List<Entry> entries = new ArrayList<>();
		for (JsonNode node : data) {
			if (COMMANDER_DECK_TYPE.equals(text(node, "type")) && text(node, "fileName") != null && text(node, "name") != null) {
				entries.add(new Entry(text(node, "code"), text(node, "fileName"), text(node, "name"),
						text(node, "releaseDate")));
			}
		}
		return entries;
	}

	/**
	 * Retire une Collector's Edition quand le même deck existe en version standard (même édition, même nom
	 * sans la mention). Leur liste de cartes est la même, seules les impressions changent. Une Collector's
	 * Edition sans version standard est conservée.
	 */
	static List<Entry> withoutCollectorsEditions(List<Entry> entries) {
		return entries.stream()
				.filter(entry -> !COLLECTORS_EDITION.matcher(entry.name()).find()
						|| entries.stream().noneMatch(other -> isStandardVersionOf(other, entry)))
				.toList();
	}

	private static boolean isStandardVersionOf(Entry candidate, Entry collectors) {
		return !COLLECTORS_EDITION.matcher(candidate.name()).find()
				&& Objects.equals(candidate.code(), collectors.code())
				&& candidate.name().equalsIgnoreCase(
						COLLECTORS_EDITION.matcher(collectors.name()).replaceAll("").replaceAll("\\s{2,}", " ").strip());
	}

	/** Du plus récent au plus ancien, puis par nom. */
	static List<Entry> newestFirst(List<Entry> entries) {
		return entries.stream()
				.sorted(Comparator.comparing((Entry entry) -> entry.releaseDate() == null ? "" : entry.releaseDate())
						.reversed()
						.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

	/**
	 * Decks dont le nom, le code d'édition, le nom d'édition ou un commandant connu contient tous les mots de
	 * la recherche (sans accents ni casse). Une recherche vide garde tous les decks. L'ordre est conservé.
	 *
	 * @param setNames   noms d'édition par code en minuscules
	 * @param commanders commandants connus, par identifiant de deck
	 */
	static List<DeckSummary> search(List<Entry> entries, String query, Map<String, String> setNames,
			Map<String, List<String>> commanders) {
		return matching(entries.stream().map(entry -> summary(entry, setNames, commanders)).toList(), query);
	}

	/** Même recherche sur des résumés déjà construits (decks importés). */
	static List<DeckSummary> matching(List<DeckSummary> decks, String query) {
		List<String> words = List.of(normalize(query).split(" ")).stream().filter(word -> !word.isEmpty()).toList();
		return decks.stream()
				.filter(deck -> {
					String haystack = normalize(deck.name() + " " + deck.code() + " " + deck.setName() + " "
							+ String.join(" ", deck.commanders()));
					return words.stream().allMatch(haystack::contains);
				})
				.toList();
	}

	static DeckSummary summary(Entry entry, Map<String, String> setNames, Map<String, List<String>> commanders) {
		String setName = entry.code() == null ? null : setNames.get(entry.code().toLowerCase(Locale.ROOT));
		return new DeckSummary(entry.fileName(), entry.name(), entry.code(), setName, entry.releaseDate(),
				commanders.getOrDefault(entry.fileName(), List.of()));
	}

	private static String normalize(String text) {
		if (text == null) {
			return "";
		}
		String plain = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
		return NOT_ALPHANUMERIC.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}
}
