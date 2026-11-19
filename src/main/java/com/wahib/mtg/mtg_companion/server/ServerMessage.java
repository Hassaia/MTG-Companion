package com.wahib.mtg.mtg_companion.server;

import java.util.List;

import com.wahib.mtg.mtg_companion.cards.CardDisplayService;
import com.wahib.mtg.mtg_companion.game.GameLog;
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

	/**
	 * Un premier joueur vient d'être tiré au sort ; envoyé juste avant l'état qui désigne le joueur actif, pour que
	 * la table joue l'animation du tirage avant de le révéler.
	 */
	public record Draw(String type) {

		public Draw() {
			this("DRAW");
		}
	}

	/** Journal de la partie (de la plus ancienne à la plus récente action) et numéro du tour en cours. */
	public record Log(String type, int turn, List<GameLog.Entry> entries) {

		public Log(int turn, List<GameLog.Entry> entries) {
			this("LOG", turn, entries);
		}
	}

	/**
	 * Carte à afficher sur la table, diffusée à tous les écrans.
	 *
	 * @param remainingMs temps restant avant la fermeture automatique
	 * @param durationMs  durée totale d'affichage, pour dessiner la barre de progression
	 */
	public record CardShown(String type, CardDisplayService.CardView card, long remainingMs, long durationMs) {

		public CardShown(CardDisplayService.CardView card, long remainingMs, long durationMs) {
			this("CARD_SHOWN", card, remainingMs, durationMs);
		}
	}

	public record CardHidden(String type) {

		public CardHidden() {
			this("CARD_HIDDEN");
		}
	}

	/** Action refusée, envoyée uniquement à son auteur. */
	public record Error(String type, String message) {

		public Error(String message) {
			this("ERROR", message);
		}
	}
}
