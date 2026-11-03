package com.wahib.mtg.mtg_companion.decks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.wahib.mtg.mtg_companion.cards.ScryfallClient;
import com.wahib.mtg.mtg_companion.decks.DeckContent.DeckCard;
import com.wahib.mtg.mtg_companion.decks.DecklistParser.Line;
import com.wahib.mtg.mtg_companion.decks.DecklistParser.Section;

import tools.jackson.databind.JsonNode;

/**
 * Import d'une decklist Moxfield : lecture, résolution des cartes sur Scryfall (par lots, impression exacte
 * d'abord), validation, puis enregistrement dans {@link CustomDeckStore}. Une carte non reconnue n'est jamais
 * remplacée par une autre : elle reste signalée et ne peut être écartée que par un choix explicite.
 */
@Service
public class DeckImportService {

	static final int MAX_NAME_LENGTH = 60;

	/** OK : impression exacte ; NAME_ONLY : carte reconnue par son nom, impression introuvable ; le reste est un problème. */
	public enum Status {
		OK, NAME_ONLY, NOT_FOUND, UNREADABLE
	}

	/** @param card carte résolue, ou null si non reconnue ; sans impression pour {@link Status#NAME_ONLY} */
	public record DraftLine(int index, String raw, int count, Status status, DeckCard card, boolean commander) {
	}

	/**
	 * @param commandersDeclared faux si la decklist n'a pas de section Commander : le choix est alors à confirmer
	 * @param recognizedCards    cartes (quantités comprises) reconnues
	 */
	public record Draft(String suggestedName, List<DraftLine> lines, boolean commandersDeclared, int totalCards,
			int recognizedCards) {
	}

	/**
	 * @param commanderLines indices (de {@link DraftLine#index()}) des commandants
	 * @param replaceId      deck importé à mettre à jour, ou null pour en créer un
	 * @param ignoreProblems écarte les lignes non reconnues au lieu de refuser l'enregistrement
	 */
	public record SaveRequest(String text, String name, List<Integer> commanderLines, String replaceId,
			boolean ignoreProblems) {
	}

	public static class DeckImportException extends RuntimeException {
		public DeckImportException(String message) {
			super(message);
		}
	}

	/** Un autre deck importé porte déjà ce nom. */
	public static class DuplicateDeckNameException extends DeckImportException {
		private final String existingId;
		private final String existingName;

		DuplicateDeckNameException(DeckContent existing) {
			super("Un deck nommé « " + existing.name() + " » existe déjà.");
			this.existingId = existing.id();
			this.existingName = existing.name();
		}

		public String existingId() {
			return existingId;
		}

		public String existingName() {
			return existingName;
		}
	}

	private final ScryfallClient scryfall;
	private final CustomDeckStore store;

	public DeckImportService(ScryfallClient scryfall, CustomDeckStore store) {
		this.scryfall = scryfall;
		this.store = store;
	}

	/** Lit et résout la decklist sans rien enregistrer. */
	public Draft preview(String text) {
		DecklistParser.Result parsed = DecklistParser.parse(text);
		return draft(parsed, null);
	}

	/**
	 * @throws DeckImportException         texte, nom ou choix invalide, ou cartes non reconnues non écartées
	 * @throws DuplicateDeckNameException  un autre deck importé a déjà ce nom
	 */
	public synchronized DeckContent save(SaveRequest request) {
		String name = validName(request.name());
		DeckContent existing = null;
		if (request.replaceId() != null) {
			existing = store.get(request.replaceId()).orElseThrow(() -> new DeckNotFoundException(request.replaceId()));
		}
		requireFreeName(name, existing == null ? null : existing.id());

		DecklistParser.Result parsed = DecklistParser.parse(request.text());
		Set<Integer> commanderIndexes = new LinkedHashSet<>(request.commanderLines() == null ? List.of() : request.commanderLines());
		Draft draft = draft(parsed, commanderIndexes);
		for (int index : commanderIndexes) {
			if (index < 0 || index >= parsed.lines().size()) {
				throw new DeckImportException("Commandant inconnu dans la liste.");
			}
		}
		long problems = draft.lines().stream().filter(line -> line.card() == null).count() + parsed.unreadable().size();
		if (problems > 0 && !request.ignoreProblems()) {
			throw new DeckImportException(problems + " ligne(s) non reconnue(s) : corrigez la liste ou écartez-les.");
		}
		List<DeckCard> commanders = new ArrayList<>();
		List<DeckCard> cards = new ArrayList<>();
		for (DraftLine line : draft.lines()) {
			if (line.card() == null) {
				continue;
			}
			(line.commander() ? commanders : cards).add(line.card());
		}
		if (commanders.isEmpty() && cards.isEmpty()) {
			throw new DeckImportException("Le deck ne contient aucune carte reconnue.");
		}
		String id = existing == null ? DeckOrigin.CUSTOM_ID_PREFIX + UUID.randomUUID().toString().replace("-", "") : existing.id();
		DeckContent deck = new DeckContent(id, name, null, LocalDate.now().toString(), merge(commanders),
				merge(cards).stream().sorted(Comparator.comparing(DeckCard::name, String.CASE_INSENSITIVE_ORDER)).toList(),
				DeckOrigin.CUSTOM);
		store.save(deck);
		return deck;
	}

	/** @throws DeckNotFoundException si ce n'est pas un deck importé */
	public synchronized DeckContent rename(String id, String newName) {
		DeckContent deck = store.get(id).orElseThrow(() -> new DeckNotFoundException(id));
		String name = validName(newName);
		requireFreeName(name, id);
		DeckContent renamed = new DeckContent(id, name, deck.code(), deck.releaseDate(), deck.commanders(), deck.cards(),
				DeckOrigin.CUSTOM);
		store.save(renamed);
		return renamed;
	}

	private void requireFreeName(String name, String ownId) {
		store.all().stream()
				.filter(other -> !other.id().equals(ownId) && other.name().equalsIgnoreCase(name))
				.findFirst()
				.ifPresent(other -> {
					throw new DuplicateDeckNameException(other);
				});
	}

	static String validName(String name) {
		String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
		if (clean.isEmpty() || clean.length() > MAX_NAME_LENGTH || clean.chars().anyMatch(Character::isISOControl)) {
			throw new DeckImportException("Le nom du deck doit faire de 1 à " + MAX_NAME_LENGTH + " caractères.");
		}
		return clean;
	}

	/**
	 * @param commanderIndexes commandants choisis, ou null pour la proposition par défaut (section Commander,
	 *                         sinon la première ligne)
	 */
	private Draft draft(DecklistParser.Result parsed, Set<Integer> commanderIndexes) {
		Map<Integer, Resolved> resolved = resolve(parsed.lines());
		Set<Integer> commanders = commanderIndexes != null ? commanderIndexes : new LinkedHashSet<>();
		if (commanderIndexes == null) {
			parsed.lines().stream().filter(line -> line.section() == Section.COMMANDER).forEach(line -> commanders.add(line.index()));
			if (commanders.isEmpty() && !parsed.lines().isEmpty()) {
				commanders.add(0);
			}
		}
		List<DraftLine> lines = new ArrayList<>();
		int total = 0;
		int recognized = 0;
		for (Line line : parsed.lines()) {
			Resolved result = resolved.get(line.index());
			DeckCard card = result.card() == null ? null
					: new DeckCard(result.card().name(), line.count(), result.card().printId(), result.card().setCode(),
							result.card().number());
			lines.add(new DraftLine(line.index(), line.raw(), line.count(), result.status(), card, commanders.contains(line.index())));
			total += line.count();
			recognized += card == null ? 0 : line.count();
		}
		for (String raw : parsed.unreadable()) {
			lines.add(new DraftLine(-1, raw, 0, Status.UNREADABLE, null, false));
		}
		String suggestion = lines.stream().filter(DraftLine::commander).map(line -> line.card() == null ? null : line.card().name())
				.filter(java.util.Objects::nonNull).findFirst().map(DeckImportService::frontFace).orElse("Deck importé");
		return new Draft(suggestion.length() > MAX_NAME_LENGTH ? suggestion.substring(0, MAX_NAME_LENGTH) : suggestion,
				lines, parsed.commandersDeclared(), total, recognized);
	}

	private record Resolved(Status status, DeckCard card) {
	}

	/** Une requête groupée pour les impressions exactes (ou noms), une seconde pour les repli par nom. */
	private Map<Integer, Resolved> resolve(List<Line> lines) {
		Map<Integer, Resolved> result = new HashMap<>();
		List<Map<String, String>> identifiers = new ArrayList<>();
		for (Line line : lines) {
			addIdentifier(identifiers, exactIdentifier(line));
		}
		List<JsonNode> cards = identifiers.isEmpty() ? List.of() : scryfall.collection(identifiers);
		List<Line> byNameFallback = new ArrayList<>();
		for (Line line : lines) {
			Optional<JsonNode> found = line.setCode() != null ? findPrint(cards, line) : findByName(cards, line.name());
			if (found.isPresent()) {
				result.put(line.index(), new Resolved(line.setCode() != null ? Status.OK : Status.NAME_ONLY, deckCard(found.get(), line.setCode() != null)));
			}
			else if (line.setCode() != null) {
				byNameFallback.add(line);
			}
			else {
				result.put(line.index(), new Resolved(Status.NOT_FOUND, null));
			}
		}
		if (!byNameFallback.isEmpty()) {
			List<Map<String, String>> nameIdentifiers = new ArrayList<>();
			byNameFallback.forEach(line -> addIdentifier(nameIdentifiers, Map.of("name", DecklistParser.normalizedName(line.name()))));
			List<JsonNode> byName = scryfall.collection(nameIdentifiers);
			for (Line line : byNameFallback) {
				Optional<JsonNode> found = findByName(byName, line.name());
				result.put(line.index(), found.isPresent()
						? new Resolved(Status.NAME_ONLY, deckCard(found.get(), false))
						: new Resolved(Status.NOT_FOUND, null));
			}
		}
		return result;
	}

	private static Map<String, String> exactIdentifier(Line line) {
		if (line.setCode() != null && line.collectorNumber() != null) {
			Map<String, String> identifier = new LinkedHashMap<>();
			identifier.put("set", line.setCode());
			identifier.put("collector_number", line.collectorNumber());
			return identifier;
		}
		return Map.of("name", DecklistParser.normalizedName(line.name()));
	}

	private static void addIdentifier(List<Map<String, String>> identifiers, Map<String, String> identifier) {
		if (!identifiers.contains(identifier)) {
			identifiers.add(identifier);
		}
	}

	private static Optional<JsonNode> findPrint(List<JsonNode> cards, Line line) {
		return cards.stream()
				.filter(card -> line.setCode().equalsIgnoreCase(text(card, "set"))
						&& line.collectorNumber().equalsIgnoreCase(text(card, "collector_number")))
				.findFirst();
	}

	/** Nom exact (casse ignorée), complet ou limité à la première face. */
	private static Optional<JsonNode> findByName(List<JsonNode> cards, String name) {
		String wanted = DecklistParser.normalizedName(name).toLowerCase(Locale.ROOT);
		return cards.stream()
				.filter(card -> {
					String cardName = text(card, "name");
					return cardName != null && (cardName.toLowerCase(Locale.ROOT).equals(wanted)
							|| frontFace(cardName).toLowerCase(Locale.ROOT).equals(wanted));
				})
				.findFirst();
	}

	private static DeckCard deckCard(JsonNode card, boolean withPrint) {
		return withPrint
				? new DeckCard(text(card, "name"), 1, text(card, "id"), text(card, "set"), text(card, "collector_number"))
				: new DeckCard(text(card, "name"), 1, null, null, null);
	}

	/** Une ligne par impression : les quantités de lignes identiques s'additionnent. */
	private static List<DeckCard> merge(List<DeckCard> cards) {
		Map<String, DeckCard> byPrint = new LinkedHashMap<>();
		for (DeckCard card : cards) {
			String key = card.printId() != null ? card.printId() : card.name().toLowerCase(Locale.ROOT);
			byPrint.merge(key, card, (first, second) ->
					new DeckCard(first.name(), first.count() + second.count(), first.printId(), first.setCode(), first.number()));
		}
		return new ArrayList<>(byPrint.values());
	}

	private static String frontFace(String name) {
		int separator = name.indexOf(" // ");
		return separator < 0 ? name : name.substring(0, separator);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}
}
