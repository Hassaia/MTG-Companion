package com.wahib.mtg.mtg_companion.server;

import java.util.List;

import com.wahib.mtg.mtg_companion.game.GameSnapshot;

/** Messages envoyés par le serveur aux navigateurs. */
public final class ServerMessage {

	private ServerMessage() {
	}

	/** État complet, envoyé à la connexion et diffusé à tous après chaque modification. */
	public record State(String type, String serverAddress, List<String> otherAddresses, GameSnapshot game) {

		public State(ServerAddresses addresses, GameSnapshot game) {
			this("STATE", addresses.primary(), addresses.others(), game);
		}
	}

	/** Action refusée, envoyée uniquement à son auteur. */
	public record Error(String type, String message) {

		public Error(String message) {
			this("ERROR", message);
		}
	}
}
