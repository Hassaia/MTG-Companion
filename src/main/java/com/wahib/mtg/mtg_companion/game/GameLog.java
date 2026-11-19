package com.wahib.mtg.mtg_companion.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Journal des actions de jeu de la partie en cours (PV, dégâts de commandant, passages de tour), chacune
 * avec l'état d'avant pour pouvoir y revenir. Conservé en mémoire seulement ; les plus anciennes entrées
 * sont oubliées au-delà de {@link #MAX_ENTRIES}.
 * <p>
 * Non thread-safe : l'appelant doit sérialiser les accès.
 */
public class GameLog {

	public static final int MAX_ENTRIES = 200;

	public static final String LIFE = "LIFE";
	public static final String COMMANDER_DAMAGE = "COMMANDER_DAMAGE";
	public static final String NEXT_TURN = "NEXT_TURN";
	public static final String SET_ACTIVE = "SET_ACTIVE";

	/**
	 * @param turn         numéro du tour au moment de l'action
	 * @param activePlayer joueur dont c'était le tour à ce moment
	 * @param player       joueur concerné (qui reçoit les PV ou les dégâts ; nouveau joueur actif pour SET_ACTIVE)
	 * @param fromPlayer   joueur dont vient le commandant (COMMANDER_DAMAGE), sinon 0
	 * @param delta        variation demandée, 0 pour un changement de tour
	 */
	public record Entry(int id, String type, int turn, int activePlayer, int player, int fromPlayer, int delta) {
	}

	private record Recorded(Entry entry, Game.Checkpoint before) {
	}

	private final List<Recorded> recorded = new ArrayList<>();
	private int nextId = 1;

	public void record(String type, int turn, int activePlayer, int player, int fromPlayer, int delta,
			Game.Checkpoint before) {
		recorded.add(new Recorded(new Entry(nextId++, type, turn, activePlayer, player, fromPlayer, delta), before));
		if (recorded.size() > MAX_ENTRIES) {
			recorded.remove(0);
		}
	}

	/** Entrées de la plus ancienne à la plus récente. */
	public List<Entry> entries() {
		return recorded.stream().map(Recorded::entry).toList();
	}

	/**
	 * Annule l'entrée {@code entryId} et toutes les suivantes.
	 *
	 * @return l'état d'avant cette entrée
	 * @throws InvalidActionException si l'entrée n'existe plus
	 */
	public Game.Checkpoint undoTo(int entryId) {
		for (int index = 0; index < recorded.size(); index++) {
			if (recorded.get(index).entry().id() == entryId) {
				Game.Checkpoint before = recorded.get(index).before();
				recorded.subList(index, recorded.size()).clear();
				return before;
			}
		}
		throw new InvalidActionException("Cette action n'est plus dans le journal");
	}

	public void clear() {
		recorded.clear();
	}
}
