package com.wahib.mtg.mtg_companion.decks;

public class DeckNotFoundException extends RuntimeException {

	public DeckNotFoundException(String deckId) {
		super("Deck inconnu : " + deckId);
	}
}
