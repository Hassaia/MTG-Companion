package com.wahib.mtg.mtg_companion.cards;

/** Scryfall injoignable ou en refus temporaire. Le message est destiné à être affiché au joueur. */
public class ScryfallUnavailableException extends RuntimeException {

	public ScryfallUnavailableException(String message) {
		super(message);
	}

	public ScryfallUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
