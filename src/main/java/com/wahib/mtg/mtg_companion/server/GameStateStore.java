package com.wahib.mtg.mtg_companion.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wahib.mtg.mtg_companion.game.GameSnapshot;
import com.wahib.mtg.mtg_companion.storage.AtomicFiles;

import tools.jackson.databind.json.JsonMapper;

/** Sauvegarde de la partie dans un fichier JSON, pour survivre à un redémarrage du serveur. */
@Component
public class GameStateStore {

	private final Path stateFile;
	private final JsonMapper jsonMapper;

	public GameStateStore(@Value("${mtg.state-file}") Path stateFile, JsonMapper jsonMapper) {
		this.stateFile = stateFile.toAbsolutePath();
		this.jsonMapper = jsonMapper;
	}

	public Path stateFile() {
		return stateFile;
	}

	/**
	 * @return la partie sauvegardée, ou vide s'il n'y a pas encore de sauvegarde
	 * @throws tools.jackson.core.JacksonException si le fichier est illisible
	 */
	public Optional<GameSnapshot> load() throws IOException {
		if (!Files.exists(stateFile)) {
			return Optional.empty();
		}
		return Optional.of(jsonMapper.readValue(Files.readString(stateFile), GameSnapshot.class));
	}

	public void save(GameSnapshot snapshot) throws IOException {
		String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
		AtomicFiles.write(stateFile, json.getBytes(StandardCharsets.UTF_8));
	}
}
