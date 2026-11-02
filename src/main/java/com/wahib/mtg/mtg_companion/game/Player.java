package com.wahib.mtg.mtg_companion.game;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Un joueur de la partie. Seule {@link Game} peut le modifier.
 */
public class Player {

	private final int id;
	private final String name;
	private int life;
	/** Dégâts de commandant reçus, par identifiant de l'adversaire. Vide hors format Commander. */
	private final Map<Integer, Integer> commanderDamageReceived = new LinkedHashMap<>();
	/** Illustration de la zone du joueur ; elle ne dépend pas de la partie en cours. */
	private GameSnapshot.Background background;

	Player(int id, String name, int life) {
		this.id = id;
		this.name = name;
		this.life = life;
	}

	public int id() {
		return id;
	}

	public String name() {
		return name;
	}

	public int life() {
		return life;
	}

	public int commanderDamageFrom(int opponentId) {
		return commanderDamageReceived.getOrDefault(opponentId, 0);
	}

	public Map<Integer, Integer> commanderDamageReceived() {
		return Collections.unmodifiableMap(commanderDamageReceived);
	}

	public boolean isEliminated() {
		return life <= 0 || commanderDamageReceived.values().stream()
				.anyMatch(damage -> damage >= Game.LETHAL_COMMANDER_DAMAGE);
	}

	public GameSnapshot.Background background() {
		return background;
	}

	void setBackground(GameSnapshot.Background background) {
		this.background = background;
	}

	void setLife(int life) {
		this.life = life;
	}

	void setCommanderDamageFrom(int opponentId, int damage) {
		commanderDamageReceived.put(opponentId, damage);
	}
}
