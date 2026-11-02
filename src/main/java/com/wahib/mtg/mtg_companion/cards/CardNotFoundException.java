package com.wahib.mtg.mtg_companion.cards;

public class CardNotFoundException extends RuntimeException {

	public CardNotFoundException(String name) {
		super("Carte introuvable : « " + name + " »");
	}
}
