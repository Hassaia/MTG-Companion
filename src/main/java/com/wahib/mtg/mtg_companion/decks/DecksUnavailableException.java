package com.wahib.mtg.mtg_companion.decks;

/** MTGJSON est injoignable (ou illisible) et rien d'utilisable n'est en cache. */
public class DecksUnavailableException extends RuntimeException {

	public DecksUnavailableException(String message) {
		super(message);
	}

	public DecksUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
