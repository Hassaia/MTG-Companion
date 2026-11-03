package com.wahib.mtg.mtg_companion.decks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit une decklist au format texte de Moxfield : {@code 1 Sol Ring (C21) 263}, {@code 1x Sol Ring},
 * marques de finition ({@code *F*}, {@code *E*}) ignorées. Les sections Commander / Mainboard / Deck
 * alimentent le deck ; Sideboard, Maybeboard, Companion et jetons sont écartés. Aucune entrée/sortie ici.
 */
final class DecklistParser {

	static final int MAX_CHARACTERS = 100_000;
	static final int MAX_LINES = 500;

	enum Section {
		COMMANDER, MAIN, IGNORED
	}

	/** @param index position dans la liste lue : sert à désigner les commandants */
	record Line(int index, String raw, int count, String name, String setCode, String collectorNumber, Section section) {
	}

	/**
	 * @param lines              cartes du deck et des commandants, dans l'ordre du fichier
	 * @param unreadable         lignes qui ne ressemblent pas à une carte
	 * @param commandersDeclared vrai si une section Commander existe
	 */
	record Result(List<Line> lines, List<String> unreadable, boolean commandersDeclared) {
	}

	private static final Pattern SECTION = Pattern.compile(
			"(?i)(commanders?|mainboard|main|deck|sideboard|maybeboard|companion|tokens?)\\s*:?");
	private static final Pattern CARD = Pattern.compile(
			"(\\d{1,3})\\s*x?\\s+(.+?)(?:\\s+\\(([A-Za-z0-9]{2,7})\\)\\s+(\\S+))?(?:\\s+\\*[A-Za-z]\\*|\\s+\\^[^^]*\\^)*");

	private DecklistParser() {
	}

	/** @throws DeckImportService.DeckImportException si le texte est vide ou dépasse les limites */
	static Result parse(String text) {
		if (text == null || text.isBlank()) {
			throw new DeckImportService.DeckImportException("La decklist est vide.");
		}
		if (text.length() > MAX_CHARACTERS) {
			throw new DeckImportService.DeckImportException("La decklist est trop longue (" + MAX_CHARACTERS + " caractères au plus).");
		}
		List<String> rows = text.strip().lines().map(String::strip).filter(row -> !row.isEmpty()).toList();
		if (rows.size() > MAX_LINES) {
			throw new DeckImportService.DeckImportException("La decklist a trop de lignes (" + MAX_LINES + " au plus).");
		}
		List<Line> lines = new ArrayList<>();
		List<String> unreadable = new ArrayList<>();
		Section section = Section.MAIN;
		boolean commandersDeclared = false;
		for (String row : rows) {
			if (row.chars().anyMatch(Character::isISOControl)) {
				unreadable.add(printable(row));
				continue;
			}
			if (SECTION.matcher(row).matches()) {
				section = sectionOf(row);
				commandersDeclared |= section == Section.COMMANDER;
				continue;
			}
			Matcher card = CARD.matcher(row);
			if (!card.matches() || Integer.parseInt(card.group(1)) == 0) {
				if (section != Section.IGNORED) {
					unreadable.add(printable(row));
				}
				continue;
			}
			if (section == Section.IGNORED) {
				continue;
			}
			String setCode = card.group(3) == null ? null : card.group(3).toLowerCase(Locale.ROOT);
			lines.add(new Line(lines.size(), row, Integer.parseInt(card.group(1)), card.group(2).strip(), setCode,
					card.group(4), section));
		}
		return new Result(List.copyOf(lines), List.copyOf(unreadable), commandersDeclared);
	}

	/** Nom Scryfall d'une carte à deux faces : {@code A / B} devient {@code A // B}. */
	static String normalizedName(String name) {
		return name.replaceAll("\\s+/\\s+", " // ").replaceAll("\\s+//\\s+", " // ");
	}

	private static Section sectionOf(String row) {
		String title = row.replace(":", "").strip().toLowerCase(Locale.ROOT);
		return switch (title) {
			case "commander", "commanders" -> Section.COMMANDER;
			case "mainboard", "main", "deck" -> Section.MAIN;
			default -> Section.IGNORED;
		};
	}

	private static String printable(String row) {
		String clean = row.replaceAll("\\p{Cntrl}", " ");
		return clean.length() > 120 ? clean.substring(0, 120) + "…" : clean;
	}
}
