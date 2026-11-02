package com.wahib.mtg.mtg_companion.server;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.wahib.mtg.mtg_companion.game.Format;

/**
 * Actions envoyées par les navigateurs. Le champ JSON {@code type} choisit le record.
 * Les clients envoient des variations, jamais des valeurs finales.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
		@JsonSubTypes.Type(value = ClientMessage.ChangeLife.class, name = "CHANGE_LIFE"),
		@JsonSubTypes.Type(value = ClientMessage.ChangeCommanderDamage.class, name = "CHANGE_COMMANDER_DAMAGE"),
		@JsonSubTypes.Type(value = ClientMessage.StartGame.class, name = "START_GAME"),
		@JsonSubTypes.Type(value = ClientMessage.Reset.class, name = "RESET")
})
public sealed interface ClientMessage {

	record ChangeLife(int player, int delta) implements ClientMessage {
	}

	/** {@code player} reçoit les dégâts infligés par le commandant de {@code fromPlayer}. */
	record ChangeCommanderDamage(int player, int fromPlayer, int delta) implements ClientMessage {
	}

	/** Le nombre de joueurs est la taille de {@code players} ; un nom vide devient « Joueur N ». */
	record StartGame(Format format, int startingLife, List<String> players) implements ClientMessage {
	}

	record Reset() implements ClientMessage {
	}
}
