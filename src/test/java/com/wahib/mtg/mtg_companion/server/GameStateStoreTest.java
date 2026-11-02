package com.wahib.mtg.mtg_companion.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wahib.mtg.mtg_companion.game.Format;
import com.wahib.mtg.mtg_companion.game.Game;
import com.wahib.mtg.mtg_companion.game.GameSettings;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

class GameStateStoreTest {

	@TempDir
	Path directory;

	private GameStateStore store() {
		return new GameStateStore(directory.resolve("state.json"), JsonMapper.builder().build());
	}

	@Test
	void loadReturnsEmptyWhenNoFile() throws Exception {
		assertThat(store().load()).isEmpty();
	}

	@Test
	void savedGameIsRestoredIdentically() throws Exception {
		Game game = new Game(new GameSettings(Format.COMMANDER, 40, List.of("Alice", "Bob", "Carole")));
		game.changeLife(1, -7);
		game.changeCommanderDamage(3, 2, 21);

		store().save(game.snapshot());
		Game restored = Game.restore(store().load().orElseThrow());

		assertThat(restored.snapshot()).isEqualTo(game.snapshot());
		assertThat(directory.resolve("state.json.tmp")).doesNotExist();
	}

	@Test
	void saveOverwritesPreviousState() throws Exception {
		Game game = new Game(new GameSettings(Format.STANDARD, 20, List.of("", "")));
		store().save(game.snapshot());
		game.changeLife(2, -4);

		store().save(game.snapshot());

		assertThat(store().load().orElseThrow().players().get(1).life()).isEqualTo(16);
	}

	@Test
	void loadFailsOnCorruptedFile() throws Exception {
		Files.writeString(directory.resolve("state.json"), "{ pas du json");

		assertThatThrownBy(() -> store().load()).isInstanceOf(JacksonException.class);
	}
}
