package com.wahib.mtg.mtg_companion.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class GameLogTest {

	private final Game game = new Game(new GameSettings(Format.COMMANDER, 40, Collections.nCopies(3, "")));
	private final GameLog log = new GameLog();

	private void changeLife(int player, int delta) {
		Game.Checkpoint before = game.checkpoint();
		game.changeLife(player, delta);
		log.record(GameLog.LIFE, game.turn(), game.activePlayerId(), player, 0, delta, before);
	}

	@Test
	void annulerUneActionRestaureLesPvEtAnnuleLesSuivantes() {
		changeLife(1, -5);
		changeLife(2, -3);
		changeLife(1, -2);
		int secondId = log.entries().get(1).id();

		game.rollbackTo(log.undoTo(secondId));

		assertThat(game.player(1).life()).isEqualTo(35);
		assertThat(game.player(2).life()).isEqualTo(40);
		assertThat(log.entries()).hasSize(1);
	}

	@Test
	void annulerUnPassageDeTourRendLaMainEtLeNumeroDeTour() {
		game.setActivePlayer(1);
		Game.Checkpoint before = game.checkpoint();
		game.nextTurn();
		log.record(GameLog.NEXT_TURN, 1, 1, game.activePlayerId(), 0, 0, before);

		game.rollbackTo(log.undoTo(log.entries().get(0).id()));

		assertThat(game.activePlayerId()).isEqualTo(1);
		assertThat(game.turn()).isEqualTo(1);
	}

	@Test
	void annulerRestaureLesDegatsDeCommandant() {
		Game.Checkpoint before = game.checkpoint();
		game.changeCommanderDamage(1, 2, 7);
		log.record(GameLog.COMMANDER_DAMAGE, 1, game.activePlayerId(), 1, 2, 7, before);

		game.rollbackTo(log.undoTo(log.entries().get(0).id()));

		assertThat(game.player(1).commanderDamageFrom(2)).isZero();
		assertThat(game.player(1).life()).isEqualTo(40);
	}

	@Test
	void uneEntreeInconnueEstRefusee() {
		assertThatThrownBy(() -> log.undoTo(42)).isInstanceOf(InvalidActionException.class);
	}

	@Test
	void leJournalOublieLesPlusAnciennesEntrees() {
		for (int index = 0; index < GameLog.MAX_ENTRIES + 10; index++) {
			changeLife(1, 1);
		}

		assertThat(log.entries()).hasSize(GameLog.MAX_ENTRIES);
	}
}
