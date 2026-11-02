package com.wahib.mtg.mtg_companion.game;

/**
 * Action refusée par les règles du jeu. Le message est destiné à être affiché à l'utilisateur.
 */
public class InvalidActionException extends RuntimeException {

	public InvalidActionException(String message) {
		super(message);
	}
}
