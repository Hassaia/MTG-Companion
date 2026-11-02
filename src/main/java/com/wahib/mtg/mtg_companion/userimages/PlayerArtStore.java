package com.wahib.mtg.mtg_companion.userimages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wahib.mtg.mtg_companion.storage.AtomicFiles;

import tools.jackson.databind.json.JsonMapper;

/** Sauvegarde de la bibliothèque d'images et des cadrages dans un fichier JSON. */
@Component
public class PlayerArtStore {

	private final Path file;
	private final JsonMapper jsonMapper;

	public PlayerArtStore(@Value("${mtg.player-art-file}") Path file, JsonMapper jsonMapper) {
		this.file = file.toAbsolutePath();
		this.jsonMapper = jsonMapper;
	}

	public Path file() {
		return file;
	}

	/**
	 * @return vide s'il n'y a pas encore de sauvegarde
	 * @throws tools.jackson.core.JacksonException si le fichier est illisible
	 */
	public Optional<PlayerArtData> load() throws IOException {
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		return Optional.of(jsonMapper.readValue(Files.readString(file), PlayerArtData.class));
	}

	public void save(PlayerArtData data) throws IOException {
		String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
		AtomicFiles.write(file, json.getBytes(StandardCharsets.UTF_8));
	}
}
