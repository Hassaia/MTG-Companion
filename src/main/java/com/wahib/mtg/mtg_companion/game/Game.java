package com.wahib.mtg.mtg_companion.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Une partie et ses règles. Les joueurs sont numérotés de 1 à N.
 * <p>
 * Non thread-safe : l'appelant doit sérialiser les accès.
 */
public class Game {

	public static final int LETHAL_COMMANDER_DAMAGE = 21;
	/** Borne des variations acceptées, pour écarter les valeurs absurdes ou les débordements. */
	public static final int MAX_ABS_DELTA = 999;

	private final GameSettings settings;
	private final List<Player> players = new ArrayList<>();

	public Game(GameSettings settings) {
		this.settings = settings;
		for (int index = 0; index < settings.playerCount(); index++) {
			players.add(new Player(index + 1, settings.playerNames().get(index), settings.startingLife()));
		}
		reset();
	}

	/** Reconstruit une partie à partir d'une sauvegarde, en vérifiant sa cohérence. */
	public static Game restore(GameSnapshot snapshot) {
		List<String> names = snapshot.players().stream().map(GameSnapshot.PlayerSnapshot::name).toList();
		Game game = new Game(new GameSettings(snapshot.format(), snapshot.startingLife(), names));
		for (int index = 0; index < snapshot.players().size(); index++) {
			GameSnapshot.PlayerSnapshot saved = snapshot.players().get(index);
			Player player = game.players.get(index);
			if (saved.id() != player.id()) {
				throw new InvalidActionException("Sauvegarde incohérente : joueur " + saved.id() + " mal placé");
			}
			player.setLife(saved.life());
			player.setBackground(saved.background());
			player.setDeckId(saved.deckId());
			for (Map.Entry<Integer, Integer> entry : saved.commanderDamage().entrySet()) {
				int opponentId = entry.getKey();
				game.requireCommanderDamageAllowed(player.id(), opponentId);
				if (entry.getValue() < 0) {
					throw new InvalidActionException("Sauvegarde incohérente : dégâts de commandant négatifs");
				}
				player.setCommanderDamageFrom(opponentId, entry.getValue());
			}
		}
		return game;
	}

	public GameSettings settings() {
		return settings;
	}

	public List<Player> players() {
		return List.copyOf(players);
	}

	public Player player(int playerId) {
		if (playerId < 1 || playerId > players.size()) {
			throw new InvalidActionException("Joueur " + playerId + " inexistant");
		}
		return players.get(playerId - 1);
	}

	public void changeLife(int playerId, int delta) {
		requireValidDelta(delta);
		Player player = player(playerId);
		player.setLife(player.life() + delta);
	}

	/**
	 * Ajoute ou retire des dégâts de commandant reçus par {@code playerId} de la part de
	 * {@code fromPlayerId}. Les PV varient d'autant, dans le sens inverse. Les dégâts ne
	 * descendent pas sous 0 : seule la part réellement retirée est rendue en PV.
	 */
	public void changeCommanderDamage(int playerId, int fromPlayerId, int delta) {
		requireValidDelta(delta);
		requireCommanderDamageAllowed(playerId, fromPlayerId);
		Player player = player(playerId);
		int currentDamage = player.commanderDamageFrom(fromPlayerId);
		int newDamage = Math.max(0, currentDamage + delta);
		int appliedDelta = newDamage - currentDamage;
		player.setCommanderDamageFrom(fromPlayerId, newDamage);
		player.setLife(player.life() - appliedDelta);
	}

	public void setBackground(int playerId, GameSnapshot.Background background) {
		player(playerId).setBackground(background);
	}

	public void setDeck(int playerId, String deckId) {
		player(playerId).setDeckId(deckId);
	}

	/**
	 * Une nouvelle partie garde le deck des joueurs qui y retrouvent leur nom (sans tenir compte de la casse),
	 * même s'ils changent de place. Un joueur remplacé (autre nom) ne reçoit pas l'ancien deck de la place.
	 * Deux joueurs de même nom : le premier prend le premier deck, le second le suivant.
	 */
	public void keepDecksOf(Game previous) {
		List<Player> available = new ArrayList<>(previous.players);
		for (Player player : players) {
			Player former = available.stream()
					.filter(candidate -> candidate.name().equalsIgnoreCase(player.name()))
					.findFirst()
					.orElse(null);
			if (former != null) {
				available.remove(former);
				player.setDeckId(former.deckId());
			}
		}
	}

	/** Une nouvelle partie garde les illustrations des joueurs qui y retrouvent leur place (même numéro). */
	public void keepBackgroundsOf(Game previous) {
		for (Player player : players) {
			if (player.id() <= previous.players.size()) {
				player.setBackground(previous.player(player.id()).background());
			}
		}
	}

	/** Remet la partie en cours à zéro : mêmes joueurs, PV de départ, aucun dégât de commandant. */
	public void reset() {
		for (Player player : players) {
			player.setLife(settings.startingLife());
			if (settings.format().commanderDamageEnabled()) {
				for (Player opponent : players) {
					if (opponent.id() != player.id()) {
						player.setCommanderDamageFrom(opponent.id(), 0);
					}
				}
			}
		}
	}

	public GameSnapshot snapshot() {
		List<GameSnapshot.PlayerSnapshot> playerSnapshots = players.stream()
				.map(player -> new GameSnapshot.PlayerSnapshot(player.id(), player.name(), player.life(),
						player.isEliminated(), player.commanderDamageReceived(), player.background(), player.deckId()))
				.toList();
		return new GameSnapshot(settings.format(), settings.startingLife(), playerSnapshots);
	}

	private void requireValidDelta(int delta) {
		if (delta == 0 || Math.abs(delta) > MAX_ABS_DELTA) {
			throw new InvalidActionException(
					"La variation doit être non nulle et comprise entre -" + MAX_ABS_DELTA + " et " + MAX_ABS_DELTA);
		}
	}

	private void requireCommanderDamageAllowed(int playerId, int fromPlayerId) {
		if (!settings.format().commanderDamageEnabled()) {
			throw new InvalidActionException("Pas de dégâts de commandant en format " + settings.format());
		}
		player(playerId);
		player(fromPlayerId);
		if (playerId == fromPlayerId) {
			throw new InvalidActionException("Un joueur ne peut pas recevoir ses propres dégâts de commandant");
		}
	}
}
