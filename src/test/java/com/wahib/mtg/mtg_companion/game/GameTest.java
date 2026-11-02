package com.wahib.mtg.mtg_companion.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameTest {

	private static Game commanderGame(int playerCount) {
		return new Game(new GameSettings(Format.COMMANDER, 40, Collections.nCopies(playerCount, "")));
	}

	private static Game standardGame() {
		return new Game(new GameSettings(Format.STANDARD, 20, List.of("Alice", "Bob")));
	}

	@Nested
	class Creation {

		@ParameterizedTest
		@ValueSource(ints = {2, 3, 4})
		void createsPlayersNumberedFromOne(int playerCount) {
			Game game = commanderGame(playerCount);

			assertThat(game.players()).extracting(Player::id)
					.containsExactlyElementsOf(IntStream.rangeClosed(1, playerCount).boxed().toList());
			assertThat(game.players()).allSatisfy(player -> assertThat(player.life()).isEqualTo(40));
		}

		@ParameterizedTest
		@ValueSource(ints = {0, 1, 5})
		void rejectsUnsupportedPlayerCount(int playerCount) {
			assertThatThrownBy(() -> commanderGame(playerCount)).isInstanceOf(InvalidActionException.class);
		}

		@Test
		void usesCustomStartingLife() {
			Game game = new Game(new GameSettings(Format.STANDARD, 30, List.of("", "")));

			assertThat(game.player(1).life()).isEqualTo(30);
			assertThat(game.player(2).life()).isEqualTo(30);
		}

		@ParameterizedTest
		@ValueSource(ints = {0, -5, 1000})
		void rejectsStartingLifeOutOfRange(int startingLife) {
			assertThatThrownBy(() -> new GameSettings(Format.STANDARD, startingLife, List.of("", "")))
					.isInstanceOf(InvalidActionException.class);
		}

		@Test
		void replacesBlankNamesWithDefaultAndTrimsOthers() {
			List<String> names = new ArrayList<>();
			names.add("  Alice ");
			names.add("   ");
			names.add(null);
			Game game = new Game(new GameSettings(Format.COMMANDER, 40, names));

			assertThat(game.players()).extracting(Player::name).containsExactly("Alice", "Joueur 2", "Joueur 3");
		}

		@Test
		void rejectsTooLongName() {
			assertThatThrownBy(() -> new GameSettings(Format.STANDARD, 20, List.of("A".repeat(21), "")))
					.isInstanceOf(InvalidActionException.class);
		}

		@Test
		void rejectsMissingFormat() {
			assertThatThrownBy(() -> new GameSettings(null, 20, List.of("", "")))
					.isInstanceOf(InvalidActionException.class);
		}

		@Test
		void commanderGameTracksDamageFromEachOpponent() {
			Game game = commanderGame(4);

			assertThat(game.player(2).commanderDamageReceived()).containsOnlyKeys(1, 3, 4);
		}

		@Test
		void standardGameHasNoCommanderDamage() {
			assertThat(standardGame().player(1).commanderDamageReceived()).isEmpty();
		}
	}

	@Nested
	class Life {

		@Test
		void appliesPositiveAndNegativeDeltas() {
			Game game = standardGame();

			game.changeLife(2, -3);
			game.changeLife(2, -5);
			game.changeLife(2, 1);

			assertThat(game.player(2).life()).isEqualTo(13);
			assertThat(game.player(1).life()).isEqualTo(20);
		}

		@Test
		void eliminatesAtZeroLife() {
			Game game = standardGame();

			game.changeLife(1, -19);
			assertThat(game.player(1).isEliminated()).isFalse();

			game.changeLife(1, -1);
			assertThat(game.player(1).isEliminated()).isTrue();
		}

		@Test
		void allowsNegativeLife() {
			Game game = standardGame();

			game.changeLife(1, -25);

			assertThat(game.player(1).life()).isEqualTo(-5);
			assertThat(game.player(1).isEliminated()).isTrue();
		}

		@Test
		void eliminatedPlayerCanBeCorrected() {
			Game game = standardGame();
			game.changeLife(1, -20);

			game.changeLife(1, 5);

			assertThat(game.player(1).life()).isEqualTo(5);
			assertThat(game.player(1).isEliminated()).isFalse();
		}

		@ParameterizedTest
		@ValueSource(ints = {0, 1000, -1000})
		void rejectsInvalidDelta(int delta) {
			Game game = standardGame();

			assertThatThrownBy(() -> game.changeLife(1, delta)).isInstanceOf(InvalidActionException.class);
			assertThat(game.player(1).life()).isEqualTo(20);
		}

		@ParameterizedTest
		@ValueSource(ints = {0, 3, -1})
		void rejectsUnknownPlayer(int playerId) {
			Game game = standardGame();

			assertThatThrownBy(() -> game.changeLife(playerId, -1))
					.isInstanceOf(InvalidActionException.class)
					.hasMessageContaining("inexistant");
		}
	}

	@Nested
	class CommanderDamage {

		@Test
		void addingDamageAlsoRemovesLife() {
			Game game = commanderGame(3);

			game.changeCommanderDamage(2, 1, 1);
			game.changeCommanderDamage(2, 1, 1);
			game.changeCommanderDamage(2, 3, 1);

			assertThat(game.player(2).commanderDamageFrom(1)).isEqualTo(2);
			assertThat(game.player(2).commanderDamageFrom(3)).isEqualTo(1);
			assertThat(game.player(2).life()).isEqualTo(37);
		}

		@Test
		void removingDamageGivesLifeBack() {
			Game game = commanderGame(2);
			game.changeCommanderDamage(1, 2, 1);
			game.changeCommanderDamage(1, 2, 1);

			game.changeCommanderDamage(1, 2, -1);

			assertThat(game.player(1).commanderDamageFrom(2)).isEqualTo(1);
			assertThat(game.player(1).life()).isEqualTo(39);
		}

		@Test
		void damageCannotGoBelowZeroAndOnlyAppliedPartIsGivenBack() {
			Game game = commanderGame(2);
			game.changeCommanderDamage(1, 2, 2);

			game.changeCommanderDamage(1, 2, -5);

			assertThat(game.player(1).commanderDamageFrom(2)).isZero();
			assertThat(game.player(1).life()).isEqualTo(40);
		}

		@Test
		void removingDamageAtZeroChangesNothing() {
			Game game = commanderGame(2);

			game.changeCommanderDamage(1, 2, -1);

			assertThat(game.player(1).commanderDamageFrom(2)).isZero();
			assertThat(game.player(1).life()).isEqualTo(40);
		}

		@Test
		void eliminatesAtTwentyOneDamageFromSameCommander() {
			Game game = commanderGame(2);
			game.changeLife(1, 100); // assez de PV pour que seuls les dégâts de commandant comptent

			game.changeCommanderDamage(1, 2, 20);
			assertThat(game.player(1).isEliminated()).isFalse();

			game.changeCommanderDamage(1, 2, 1);
			assertThat(game.player(1).isEliminated()).isTrue();
			assertThat(game.player(1).life()).isEqualTo(119);
		}

		@Test
		void damageFromDifferentCommandersIsNotCumulated() {
			Game game = commanderGame(3);

			game.changeCommanderDamage(1, 2, 15);
			game.changeCommanderDamage(1, 3, 15);

			assertThat(game.player(1).life()).isEqualTo(10);
			assertThat(game.player(1).isEliminated()).isFalse();
		}

		@Test
		void correctionBelowTwentyOneRevivesPlayer() {
			Game game = commanderGame(2);
			game.changeCommanderDamage(1, 2, 21);

			game.changeCommanderDamage(1, 2, -1);

			assertThat(game.player(1).isEliminated()).isFalse();
			assertThat(game.player(1).life()).isEqualTo(20);
		}

		@Test
		void eliminatedPlayerStillAcceptsDamageChanges() {
			Game game = commanderGame(2);
			game.changeCommanderDamage(1, 2, 21);

			game.changeCommanderDamage(1, 2, 1);

			assertThat(game.player(1).commanderDamageFrom(2)).isEqualTo(22);
		}

		@Test
		void rejectedInStandardFormat() {
			Game game = standardGame();

			assertThatThrownBy(() -> game.changeCommanderDamage(1, 2, 1))
					.isInstanceOf(InvalidActionException.class);
			assertThat(game.player(1).life()).isEqualTo(20);
		}

		@Test
		void rejectsDamageFromSelf() {
			Game game = commanderGame(2);

			assertThatThrownBy(() -> game.changeCommanderDamage(1, 1, 1))
					.isInstanceOf(InvalidActionException.class);
		}

		@Test
		void rejectsUnknownPlayers() {
			Game game = commanderGame(2);

			assertThatThrownBy(() -> game.changeCommanderDamage(3, 1, 1)).isInstanceOf(InvalidActionException.class);
			assertThatThrownBy(() -> game.changeCommanderDamage(1, 3, 1)).isInstanceOf(InvalidActionException.class);
		}

		@Test
		void rejectsZeroDelta() {
			Game game = commanderGame(2);

			assertThatThrownBy(() -> game.changeCommanderDamage(1, 2, 0)).isInstanceOf(InvalidActionException.class);
		}
	}

	@Nested
	class Reset {

		@Test
		void restoresStartingLifeAndClearsDamageButKeepsSettings() {
			Game game = new Game(new GameSettings(Format.COMMANDER, 30, List.of("Alice", "Bob", "Carole")));
			game.changeLife(1, -12);
			game.changeCommanderDamage(2, 3, 21);

			game.reset();

			assertThat(game.players()).allSatisfy(player -> {
				assertThat(player.life()).isEqualTo(30);
				assertThat(player.commanderDamageReceived().values()).containsOnly(0);
				assertThat(player.isEliminated()).isFalse();
			});
			assertThat(game.players()).extracting(Player::name).containsExactly("Alice", "Bob", "Carole");
			assertThat(game.settings().format()).isEqualTo(Format.COMMANDER);
		}
	}

	@Nested
	class SnapshotAndRestore {

		@Test
		void snapshotReflectsState() {
			Game game = commanderGame(2);
			game.changeCommanderDamage(1, 2, 21);

			GameSnapshot snapshot = game.snapshot();

			assertThat(snapshot.format()).isEqualTo(Format.COMMANDER);
			assertThat(snapshot.startingLife()).isEqualTo(40);
			GameSnapshot.PlayerSnapshot first = snapshot.players().getFirst();
			assertThat(first.life()).isEqualTo(19);
			assertThat(first.eliminated()).isTrue();
			assertThat(first.commanderDamage()).isEqualTo(Map.of(2, 21));
		}

		@Test
		void restoreRebuildsAnEquivalentGame() {
			Game game = new Game(new GameSettings(Format.COMMANDER, 40, List.of("Alice", "Bob", "Carole")));
			game.changeLife(1, -7);
			game.changeCommanderDamage(3, 2, 4);

			Game restored = Game.restore(game.snapshot());

			assertThat(restored.snapshot()).isEqualTo(game.snapshot());
		}

		@Test
		void restoreRejectsInconsistentCommanderDamage() {
			GameSnapshot corrupted = new GameSnapshot(Format.COMMANDER, 40, List.of(
					new GameSnapshot.PlayerSnapshot(1, "A", 40, false, Map.of(1, 3)),
					new GameSnapshot.PlayerSnapshot(2, "B", 40, false, Map.of())));

			assertThatThrownBy(() -> Game.restore(corrupted)).isInstanceOf(InvalidActionException.class);
		}
	}
}
