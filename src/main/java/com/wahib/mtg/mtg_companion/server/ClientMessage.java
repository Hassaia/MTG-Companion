package com.wahib.mtg.mtg_companion.server;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.wahib.mtg.mtg_companion.game.Format;
import com.wahib.mtg.mtg_companion.userimages.Framing;

/**
 * Actions envoyées par les navigateurs. Le champ JSON {@code type} choisit le record.
 * Les clients envoient des variations, jamais des valeurs finales.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = ClientMessage.ChangeLife.class, name = "CHANGE_LIFE"),
		@JsonSubTypes.Type(value = ClientMessage.ChangeCommanderDamage.class, name = "CHANGE_COMMANDER_DAMAGE"),
		@JsonSubTypes.Type(value = ClientMessage.StartGame.class, name = "START_GAME"),
		@JsonSubTypes.Type(value = ClientMessage.Reset.class, name = "RESET"),
		@JsonSubTypes.Type(value = ClientMessage.UndoTo.class, name = "UNDO_TO"),
		@JsonSubTypes.Type(value = ClientMessage.SetSurvivesAtZero.class, name = "SET_SURVIVES_AT_ZERO"),
		@JsonSubTypes.Type(value = ClientMessage.NextTurn.class, name = "NEXT_TURN"),
		@JsonSubTypes.Type(value = ClientMessage.SetActivePlayer.class, name = "SET_ACTIVE_PLAYER"),
		@JsonSubTypes.Type(value = ClientMessage.SetPlayerBackground.class, name = "SET_PLAYER_BACKGROUND"),
		@JsonSubTypes.Type(value = ClientMessage.ClearPlayerBackground.class, name = "CLEAR_PLAYER_BACKGROUND"),
		@JsonSubTypes.Type(value = ClientMessage.SetPlayerDeck.class, name = "SET_PLAYER_DECK"),
		@JsonSubTypes.Type(value = ClientMessage.ClearPlayerDeck.class, name = "CLEAR_PLAYER_DECK")
})
public sealed interface ClientMessage {

	record ChangeLife(int player, int delta) implements ClientMessage {
	}

	/** {@code player} reçoit les dégâts infligés par le commandant de {@code fromPlayer}. */
	record ChangeCommanderDamage(int player, int fromPlayer, int delta) implements ClientMessage {
	}

	/**
	 * Le nombre de joueurs est la taille de {@code players} ; un nom vide devient « Joueur N ».
	 * {@code firstPlayer} : joueur qui commence, ou null pour le tirer au sort.
	 */
	record StartGame(Format format, int startingLife, List<String> players, Integer firstPlayer)
			implements ClientMessage {

		public StartGame(Format format, int startingLife, List<String> players) {
			this(format, startingLife, players, null);
		}
	}

	/** Active ou désactive l'effet « ne peut pas perdre » (Platinum Angel…) d'un joueur. */
	record SetSurvivesAtZero(int player, boolean enabled) implements ClientMessage {
	}

	/** Annule l'action {@code entryId} du journal et toutes les suivantes. */
	record UndoTo(int entryId) implements ClientMessage {
	}

	record Reset() implements ClientMessage {
	}

	/** Passe le tour au joueur suivant. */
	record NextTurn() implements ClientMessage {
	}

	/** Donne la main à {@code player} (correction du tour). */
	record SetActivePlayer(int player) implements ClientMessage {
	}

	/**
	 * Choisit le fond d'un joueur. Seule l'identité de l'image est envoyée (Scryfall : carte, impression et
	 * face ; image importée : identifiant) : le serveur retrouve le reste dans son cache ou sa bibliothèque.
	 * {@code framings} (nombre de joueurs de la disposition → cadrage) ne contient que les cadrages modifiés ;
	 * les autres sont retrouvés dans les préférences du joueur.
	 *
	 * @param kind {@code SCRYFALL} (par défaut) ou {@code UPLOAD}
	 */
	record SetPlayerBackground(int player, String kind, String imageId, String cardName, String printId, Integer face,
			Map<Integer, Framing> framings) implements ClientMessage {
	}

	record ClearPlayerBackground(int player) implements ClientMessage {
	}

	/** Choisit le préconstruit officiel d'un joueur ; {@code deckId} est le {@code fileName} MTGJSON du catalogue. */
	record SetPlayerDeck(int player, String deckId) implements ClientMessage {
	}

	record ClearPlayerDeck(int player) implements ClientMessage {
	}
}
