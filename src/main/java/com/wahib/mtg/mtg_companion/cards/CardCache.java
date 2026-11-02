package com.wahib.mtg.mtg_companion.cards;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wahib.mtg.mtg_companion.storage.AtomicFiles;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cache sur disque des cartes déjà affichées et de leurs images : une carte vue une fois se
 * réaffiche sans rappeler Scryfall, y compris sans connexion Internet. Il n'expire pas ; pour le
 * vider, il suffit de supprimer son dossier.
 * <pre>
 * card-cache/cards/&lt;empreinte du nom&gt;.json
 * card-cache/images/&lt;id scryfall&gt;-&lt;face&gt;.jpg
 * </pre>
 */
@Component
public class CardCache {

	private static final Logger log = LoggerFactory.getLogger(CardCache.class);
	/** Seuls noms acceptés pour une image : empêche toute lecture hors du dossier du cache. */
	private static final Pattern IMAGE_FILE_NAME =
			Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]\\.jpg");

	private final Path cardsDirectory;
	private final Path imagesDirectory;
	private final JsonMapper jsonMapper;

	public CardCache(@Value("${mtg.card-cache-dir}") Path cacheDirectory, JsonMapper jsonMapper) {
		Path root = cacheDirectory.toAbsolutePath();
		this.cardsDirectory = root.resolve("cards");
		this.imagesDirectory = root.resolve("images");
		this.jsonMapper = jsonMapper;
	}

	public static boolean isValidImageFileName(String fileName) {
		return fileName != null && IMAGE_FILE_NAME.matcher(fileName).matches();
	}

	/** @param name nom anglais de la carte, sans tenir compte de la casse */
	public Optional<Card> find(String name) {
		Path file = cardFile(name);
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		return read(file);
	}

	public void save(Card card) throws IOException {
		byte[] json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(card);
		AtomicFiles.write(cardFile(card.name()), json);
	}

	/** Toutes les cartes du cache, pour proposer des suggestions quand Scryfall est injoignable. */
	public List<Card> all() {
		List<Card> cards = new ArrayList<>();
		if (!Files.isDirectory(cardsDirectory)) {
			return cards;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(cardsDirectory, "*.json")) {
			files.forEach(file -> read(file).ifPresent(cards::add));
		}
		catch (IOException e) {
			log.warn("Lecture impossible du cache de cartes {}", cardsDirectory, e);
		}
		return cards;
	}

	public boolean hasImage(String fileName) {
		return imagePath(fileName).isPresent();
	}

	/** @return le fichier image s'il est en cache ; vide si absent ou si le nom n'est pas valide */
	public Optional<Path> imagePath(String fileName) {
		if (!isValidImageFileName(fileName)) {
			return Optional.empty();
		}
		Path file = imagesDirectory.resolve(fileName);
		return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
	}

	public void saveImage(String fileName, byte[] content) throws IOException {
		if (!isValidImageFileName(fileName)) {
			throw new IllegalArgumentException("Nom d'image invalide : " + fileName);
		}
		AtomicFiles.write(imagesDirectory.resolve(fileName), content);
	}

	private Optional<Card> read(Path file) {
		try {
			return Optional.of(jsonMapper.readValue(Files.readString(file), Card.class));
		}
		catch (IOException | JacksonException e) {
			log.warn("Carte en cache illisible, ignorée : {}", file, e);
			return Optional.empty();
		}
	}

	/** Le nom d'une carte peut contenir n'importe quel caractère : le fichier est nommé d'après son empreinte. */
	private Path cardFile(String name) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
			return cardsDirectory.resolve(HexFormat.of().formatHex(digest) + ".json");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 est toujours disponible dans le JDK", e);
		}
	}
}
