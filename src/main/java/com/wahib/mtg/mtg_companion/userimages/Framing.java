package com.wahib.mtg.mtg_companion.userimages;

import com.wahib.mtg.mtg_companion.game.InvalidActionException;

/** Cadrage d'une image dans une zone : de 0 % (gauche, haut) à 100 % (droite, bas). */
public record Framing(int x, int y) {

	public static final Framing CENTERED = new Framing(50, 50);

	public Framing {
		if (x < 0 || x > 100 || y < 0 || y > 100) {
			throw new InvalidActionException("Cadrage invalide : les positions vont de 0 à 100");
		}
	}
}
