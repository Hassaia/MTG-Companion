package com.wahib.mtg.mtg_companion.decks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wahib.mtg.mtg_companion.storage.AtomicFiles;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache sur disque des données MTGJSON : le catalogue (rafraîchi par {@link DeckService}), les noms
 * d'édition, les decks déjà ouverts et l'index de leurs commandants. Pour tout vider, il suffit de
 * supprimer le dossier.
 * <pre>
 * deck-cache/catalog.json          préconstruits Commander du catalogue, avec leur date de récupération
 * deck-cache/set-names.json        nom des éditions par code, avec leur date de récupération
 * deck-cache/commanders.json       index « deck → commandants » (rempli à chaque deck téléchargé)
 * deck-cache/decks/&lt;id&gt;.json       cartes réduites d'un deck (sans traductions, rulings ni jetons)
 * </pre>
 */
@Component
public class DeckCache {

	private static final Logger log = LoggerFactory.getLogger(DeckCache.class);

	record CachedCatalog(Instant fetchedAt, List<DeckCatalog.Entry> entries) {
	}

	record CachedSetNames(Instant fetchedAt, Map<String, String> names) {
	}

	/** Petit index des commandants : de quoi chercher « Cloud » sans télécharger les 198 decks. */
	record CommanderIndex(Map<String, List<String>> decks) {
	}

	private final Path root;
	private final JsonMapper jsonMapper;

	public DeckCache(@Value("${mtg.deck-cache-dir}") Path cacheDirectory, JsonMapper jsonMapper) {
		this.root = cacheDirectory.toAbsolutePath();
		this.jsonMapper = jsonMapper;
	}

	Optional<CachedCatalog> catalog() {
		return read(root.resolve("catalog.json"), CachedCatalog.class);
	}

	void saveCatalog(CachedCatalog catalog) throws IOException {
		write(root.resolve("catalog.json"), catalog);
	}

	Optional<CachedSetNames> setNames() {
		return read(root.resolve("set-names.json"), CachedSetNames.class);
	}

	void saveSetNames(CachedSetNames setNames) throws IOException {
		write(root.resolve("set-names.json"), setNames);
	}

	Map<String, List<String>> commanders() {
		return read(root.resolve("commanders.json"), CommanderIndex.class).map(CommanderIndex::decks).orElse(Map.of());
	}

	void saveCommanders(Map<String, List<String>> commanders) throws IOException {
		write(root.resolve("commanders.json"), new CommanderIndex(commanders));
	}

	/** @param id identifiant déjà validé par {@link DeckService#isValidId} */
	Optional<DeckContent> deck(String id) {
		return read(root.resolve("decks").resolve(id + ".json"), DeckContent.class);
	}

	void saveDeck(DeckContent deck) throws IOException {
		write(root.resolve("decks").resolve(deck.id() + ".json"), deck);
	}

	private <T> Optional<T> read(Path file, Class<T> type) {
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try {
			return Optional.of(jsonMapper.readValue(Files.readString(file), type));
		}
		catch (IOException | JacksonException e) {
			log.warn("Fichier du cache des decks illisible, ignoré : {}", file, e);
			return Optional.empty();
		}
	}

	private void write(Path file, Object content) throws IOException {
		AtomicFiles.write(file, jsonMapper.writeValueAsBytes(content));
	}
}
