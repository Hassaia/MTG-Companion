package com.wahib.mtg.mtg_companion.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Paramètres d'une partie, validés et normalisés à la construction :
 * un nom vide ou absent devient « Joueur N ».
 */
public record GameSettings(Format format, int startingLife, List<String> playerNames) {

	public static final int MIN_PLAYERS = 2;
	public static final int MAX_PLAYERS = 4;
	public static final int MIN_STARTING_LIFE = 1;
	public static final int MAX_STARTING_LIFE = 999;
	public static final int MAX_NAME_LENGTH = 20;

	public GameSettings {
		if (format == null) {
			throw new InvalidActionException("Format manquant");
		}
		if (startingLife < MIN_STARTING_LIFE || startingLife > MAX_STARTING_LIFE) {
			throw new InvalidActionException(
					"Les PV de départ doivent être entre " + MIN_STARTING_LIFE + " et " + MAX_STARTING_LIFE);
		}
		if (playerNames == null || playerNames.size() < MIN_PLAYERS || playerNames.size() > MAX_PLAYERS) {
			throw new InvalidActionException(
					"Le nombre de joueurs doit être entre " + MIN_PLAYERS + " et " + MAX_PLAYERS);
		}
		playerNames = normalizeNames(playerNames);
	}

	public static GameSettings defaults() {
		return new GameSettings(Format.COMMANDER, Format.COMMANDER.defaultStartingLife(),
				List.of("", "", "", ""));
	}

	public int playerCount() {
		return playerNames.size();
	}

	private static List<String> normalizeNames(List<String> rawNames) {
		List<String> names = new ArrayList<>();
		for (int index = 0; index < rawNames.size(); index++) {
			String raw = rawNames.get(index);
			String name = raw == null ? "" : raw.strip();
			if (name.length() > MAX_NAME_LENGTH) {
				throw new InvalidActionException(
						"Le nom « " + name + " » dépasse " + MAX_NAME_LENGTH + " caractères");
			}
			names.add(name.isEmpty() ? "Joueur " + (index + 1) : name);
		}
		return List.copyOf(names);
	}
}
