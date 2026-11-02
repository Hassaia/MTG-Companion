package com.wahib.mtg.mtg_companion.server;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wahib.mtg.mtg_companion.game.GameSnapshot;

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

	/**
	 * Écrit d'abord dans un fichier temporaire puis le renomme : un arrêt brutal pendant
	 * l'écriture ne laisse jamais un fichier à moitié écrit.
	 */
	public void save(GameSnapshot snapshot) throws IOException {
		Path directory = stateFile.getParent();
		Files.createDirectories(directory);
		Path temporaryFile = directory.resolve(stateFile.getFileName() + ".tmp");
		Files.writeString(temporaryFile, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
		try {
			Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e) {
			Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
