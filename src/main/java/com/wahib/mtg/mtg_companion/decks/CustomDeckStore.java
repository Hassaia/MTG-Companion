package com.wahib.mtg.mtg_companion.decks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wahib.mtg.mtg_companion.storage.AtomicFiles;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decks importés par les joueurs, partagés par toute la table. Contrairement à {@code deck-cache/}, ce dossier
 * n'est pas un cache : il est à sauvegarder avec la partie.
 * <pre>
 * custom-decks/&lt;id&gt;.json    un {@link DeckContent} par deck ({@code custom_<uuid>})
 * </pre>
 * Les decks sont chargés au démarrage et gardés en mémoire ; chaque modification est écrite de façon atomique.
 */
@Component
public class CustomDeckStore {

	private static final Logger log = LoggerFactory.getLogger(CustomDeckStore.class);

	private final Path root;
	private final JsonMapper jsonMapper;
	private final Map<String, DeckContent> decks = new LinkedHashMap<>();

	public CustomDeckStore(@Value("${mtg.custom-decks-dir}") Path directory, JsonMapper jsonMapper) {
		this.root = directory.toAbsolutePath();
		this.jsonMapper = jsonMapper;
		load();
	}

	public synchronized List<DeckContent> all() {
		return new ArrayList<>(decks.values());
	}

	public synchronized Optional<DeckContent> get(String id) {
		return Optional.ofNullable(decks.get(id));
	}

	/** Crée ou remplace un deck. */
	public synchronized void save(DeckContent deck) {
		requireCustomId(deck.id());
		try {
			AtomicFiles.write(root.resolve(deck.id() + ".json"), jsonMapper.writeValueAsBytes(deck));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Impossible d'enregistrer le deck " + deck.id(), e);
		}
		decks.put(deck.id(), deck);
	}

	/** @return faux si le deck n'existe pas */
	public synchronized boolean delete(String id) {
		if (!decks.containsKey(id)) {
			return false;
		}
		requireCustomId(id);
		try {
			Files.deleteIfExists(root.resolve(id + ".json"));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Impossible de supprimer le deck " + id, e);
		}
		decks.remove(id);
		return true;
	}

	private static void requireCustomId(String id) {
		if (!DeckService.isValidId(id) || DeckOrigin.of(id) != DeckOrigin.CUSTOM) {
			throw new IllegalArgumentException("Identifiant de deck importé invalide : " + id);
		}
	}

	private void load() {
		if (!Files.isDirectory(root)) {
			return;
		}
		try (Stream<Path> files = Files.list(root)) {
			files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().forEach(file -> {
				try {
					DeckContent deck = jsonMapper.readValue(Files.readString(file), DeckContent.class);
					String expectedName = deck.id() + ".json";
					if (!expectedName.equals(file.getFileName().toString()) || !DeckService.isValidId(deck.id())
							|| DeckOrigin.of(deck.id()) != DeckOrigin.CUSTOM) {
						log.warn("Deck importé ignoré (identifiant incohérent) : {}", file);
						return;
					}
					decks.put(deck.id(), deck);
				}
				catch (IOException | JacksonException e) {
					log.warn("Deck importé illisible, ignoré : {}", file, e);
				}
			});
		}
		catch (IOException e) {
			log.warn("Dossier des decks importés illisible : {}", root, e);
		}
	}
}
