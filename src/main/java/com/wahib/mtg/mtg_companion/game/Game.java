package com.wahib.mtg.mtg_companion.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
	private int activePlayerId;
	/** Joueur qui a commencé : le numéro de tour augmente quand on revient à lui. */
	private int firstPlayerId;
	private int turn = 1;

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
			player.setSurvivesAtZero(saved.survivesAtZero());
			for (Map.Entry<Integer, Integer> entry : saved.commanderDamage().entrySet()) {
				int opponentId = entry.getKey();
				game.requireCommanderDamageAllowed(player.id(), opponentId);
				if (entry.getValue() < 0) {
					throw new InvalidActionException("Sauvegarde incohérente : dégâts de commandant négatifs");
				}
				player.setCommanderDamageFrom(opponentId, entry.getValue());
			}
		}
		// Ancienne sauvegarde sans joueur actif, ou valeur incohérente : on garde le tirage au sort du constructeur
		Integer active = snapshot.activePlayer();
		if (active != null && active >= 1 && active <= game.players.size()) {
			game.activePlayerId = active;
			game.firstPlayerId = active;
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

	/** Effet de carte : le joueur ne peut pas perdre (PV à 0 ou moins, dégâts de commandant mortels) ; les valeurs continuent de varier. */
	public void setSurvivesAtZero(int playerId, boolean survivesAtZero) {
		player(playerId).setSurvivesAtZero(survivesAtZero);
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

	/** Joueur dont c'est le tour (numéro), tiré au sort au début de la partie. */
	public int activePlayerId() {
		return activePlayerId;
	}

	/** Numéro du tour en cours, à partir de 1 ; chaque passage au joueur suivant l'incrémente. */
	public int turn() {
		return turn;
	}

	/** Tire au sort le joueur qui commence. */
	public void drawFirstPlayer() {
		activePlayerId = ThreadLocalRandom.current().nextInt(players.size()) + 1;
		firstPlayerId = activePlayerId;
	}

	/**
	 * État de jeu (PV, dégâts de commandant, tour) à un instant donné, pour revenir en arrière après une erreur.
	 * Les illustrations et les decks n'en font pas partie : un retour en arrière ne les touche pas.
	 */
	public record Checkpoint(Map<Integer, Integer> lives, Map<Integer, Map<Integer, Integer>> commanderDamage,
			int activePlayerId, int firstPlayerId, int turn) {
	}

	public Checkpoint checkpoint() {
		Map<Integer, Integer> lives = new java.util.HashMap<>();
		Map<Integer, Map<Integer, Integer>> damage = new java.util.HashMap<>();
		for (Player player : players) {
			lives.put(player.id(), player.life());
			damage.put(player.id(), Map.copyOf(player.commanderDamageReceived()));
		}
		return new Checkpoint(lives, damage, activePlayerId, firstPlayerId, turn);
	}

	public void rollbackTo(Checkpoint checkpoint) {
		for (Player player : players) {
			player.setLife(checkpoint.lives().get(player.id()));
			for (Map.Entry<Integer, Integer> entry : checkpoint.commanderDamage().get(player.id()).entrySet()) {
				player.setCommanderDamageFrom(entry.getKey(), entry.getValue());
			}
		}
		activePlayerId = checkpoint.activePlayerId();
		firstPlayerId = checkpoint.firstPlayerId();
		turn = checkpoint.turn();
	}

	/** Donne la main à un joueur. Pendant le premier tour, c'est aussi lui qui est considéré comme ayant commencé. */
	public void setActivePlayer(int playerId) {
		activePlayerId = player(playerId).id();
		if (turn == 1) {
			firstPlayerId = activePlayerId;
		}
	}

	/**
	 * Passe au joueur suivant (par numéro), en sautant les joueurs éliminés tant qu'il en reste un en vie.
	 * Le numéro de tour augmente quand le tour de table revient au joueur qui a commencé (ou le dépasse, s'il est éliminé).
	 */
	public void nextTurn() {
		int count = players.size();
		for (int step = 1; step <= count; step++) {
			Player candidate = players.get((activePlayerId - 1 + step) % count);
			if (!candidate.isEliminated()) {
				int before = (activePlayerId - firstPlayerId + count) % count;
				int after = (candidate.id() - firstPlayerId + count) % count;
				activePlayerId = candidate.id();
				if (after <= before) {
					turn++;
				}
				return;
			}
		}
	}

	/** Remet la partie en cours à zéro : mêmes joueurs, PV de départ, aucun dégât de commandant, premier joueur tiré au sort. */
	public void reset() {
		drawFirstPlayer();
		turn = 1;
		for (Player player : players) {
			player.setLife(settings.startingLife());
			player.setSurvivesAtZero(false);
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
						player.isEliminated(), player.commanderDamageReceived(), player.background(), player.deckId(),
							player.survivesAtZero()))
				.toList();
		return new GameSnapshot(settings.format(), settings.startingLife(), playerSnapshots, activePlayerId);
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
