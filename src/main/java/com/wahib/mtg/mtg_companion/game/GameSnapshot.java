package com.wahib.mtg.mtg_companion.game;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Photographie immuable de l'état complet d'une partie : c'est ce qui est diffusé aux clients
 * et sauvegardé sur disque.
 */
public record GameSnapshot(Format format, int startingLife, List<PlayerSnapshot> players) {

	public GameSnapshot {
		players = List.copyOf(players);
	}

	/**
	 * @param eliminated calculé à partir des PV et des dégâts ; ignoré lors d'une restauration
	 */
	public record PlayerSnapshot(int id, String name, int life, boolean eliminated,
			Map<Integer, Integer> commanderDamage) {

		public PlayerSnapshot {
			// TreeMap : adversaires triés par numéro, pour un affichage stable
			commanderDamage = commanderDamage == null ? Map.of()
					: Collections.unmodifiableMap(new TreeMap<>(commanderDamage));
		}
	}
}
