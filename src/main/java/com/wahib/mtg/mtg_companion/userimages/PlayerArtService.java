package com.wahib.mtg.mtg_companion.userimages;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.wahib.mtg.mtg_companion.game.InvalidActionException;
import com.wahib.mtg.mtg_companion.storage.AtomicFiles;

/**
 * Bibliothèque d'images partagée et cadrages mémorisés par joueur, image et disposition.
 * <p>
 * Les méthodes sont synchronisées. {@code GameService} prend toujours son propre verrou avant celui-ci,
 * jamais l'inverse.
 */
@Service
public class PlayerArtService {

	/** Taille maximale d'un fichier importé. */
	public static final long MAX_BYTES = 15L * 1024 * 1024;
	public static final int MIN_PLAYERS = 2;
	public static final int MAX_PLAYERS = 4;

	private static final Logger log = LoggerFactory.getLogger(PlayerArtService.class);
	private static final int MAX_NAME_LENGTH = 80;
	private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final Pattern IMAGE_ID = Pattern.compile(UUID_REGEX);
	/** Identifiant d'image d'un cadrage : image importée, ou impression Scryfall et face. */
	private static final Pattern FRAMING_IMAGE_ID = Pattern.compile(UUID_REGEX + "(-[0-9])?");

	private final PlayerArtStore store;
	private final Path imagesDirectory;
	private final PlayerArtData data;

	public PlayerArtService(PlayerArtStore store, @Value("${mtg.user-images-dir}") Path imagesDirectory) {
		this.store = store;
		this.imagesDirectory = imagesDirectory.toAbsolutePath();
		this.data = load(store);
	}

	/** Identifiant d'une image de la bibliothèque (UUID en minuscules). */
	public static boolean isValidImageId(String imageId) {
		return imageId != null && IMAGE_ID.matcher(imageId).matches();
	}

	// --- Bibliothèque

	/**
	 * Enregistre l'image telle quelle, sans la redimensionner ni la recompresser.
	 *
	 * @param originalName nom du fichier chez le joueur, conservé (nettoyé) pour l'affichage seulement
	 * @throws UserImageException si le fichier est trop gros, d'un format non pris en charge ou impossible à enregistrer
	 */
	public synchronized UserImage add(String originalName, byte[] content) {
		if (content.length == 0) {
			throw new UserImageException(400, "Le fichier est vide.");
		}
		if (content.length > MAX_BYTES) {
			throw new UserImageException(413, "L'image dépasse " + MAX_BYTES / (1024 * 1024) + " Mo.");
		}
		ImageFormat.Detected format = ImageFormat.detect(content).orElseThrow(() -> new UserImageException(415,
				"Format non pris en charge : utilisez une image JPEG, PNG ou WebP de moins de "
						+ ImageFormat.MAX_DIMENSION + " px de côté."));
		// Le nom du fichier stocké est généré ici : rien de ce qui vient du joueur n'entre dans le chemin
		String id = UUID.randomUUID().toString();
		Path file = imagesDirectory.resolve(id + "." + format.extension());
		UserImage image = new UserImage(id, cleanName(originalName), format.contentType(), format.extension(),
				format.width(), format.height(), content.length, Instant.now().toString());
		try {
			AtomicFiles.write(file, content);
		}
		catch (IOException e) {
			throw new UserImageException(500, "Impossible d'enregistrer l'image sur le serveur.", e);
		}
		data.library().put(id, image);
		try {
			store.save(data);
		}
		catch (IOException | RuntimeException e) {
			data.library().remove(id);
			deleteFile(file);
			throw new UserImageException(500, "Impossible d'enregistrer la bibliothèque sur le serveur.", e);
		}
		return image;
	}

	public synchronized List<UserImage> list() {
		return new ArrayList<>(data.library().values());
	}

	public synchronized Optional<UserImage> find(String imageId) {
		return Optional.ofNullable(imageId == null ? null : data.library().get(imageId));
	}

	/** @return le fichier de l'image, vide si l'image est inconnue ou si son fichier a disparu */
	public synchronized Optional<Path> file(String imageId) {
		return find(imageId)
				.map(image -> imagesDirectory.resolve(image.id() + "." + image.extension()))
				.filter(Files::isRegularFile);
	}

	/**
	 * Retire l'image de la bibliothèque, supprime son fichier et les cadrages de tous les joueurs pour cette
	 * image. Retirer le fond des joueurs qui l'utilisent est l'affaire de l'appelant.
	 *
	 * @return faux si l'image n'existe pas
	 */
	public synchronized boolean delete(String imageId) {
		UserImage image = find(imageId).orElse(null);
		if (image == null) {
			return false;
		}
		data.library().remove(imageId);
		data.framing().values().forEach(byImage -> byImage.remove(imageId));
		save();
		deleteFile(imagesDirectory.resolve(image.id() + "." + image.extension()));
		return true;
	}

	// --- Cadrages

	/** Cadrage enregistré, ou 50 % / 50 % si le joueur n'en a pas réglé pour cette image et cette disposition. */
	public synchronized Framing framing(int playerId, String imageId, int playerCount) {
		return data.framing().getOrDefault(playerId, Map.of()).getOrDefault(imageId, Map.of())
				.getOrDefault(playerCount, Framing.CENTERED);
	}

	/** Tous les cadrages d'un joueur, par image puis par nombre de joueurs. */
	public synchronized Map<String, Map<Integer, Framing>> framingsOf(int playerId) {
		Map<String, Map<Integer, Framing>> copy = new LinkedHashMap<>();
		data.framing().getOrDefault(playerId, Map.of())
				.forEach((imageId, byCount) -> copy.put(imageId, new LinkedHashMap<>(byCount)));
		return copy;
	}

	public synchronized void setFraming(int playerId, String imageId, int playerCount, Framing framing) {
		requireFramingKey(imageId, playerCount);
		data.framing().computeIfAbsent(playerId, id -> new LinkedHashMap<>())
				.computeIfAbsent(imageId, id -> new LinkedHashMap<>())
				.put(playerCount, framing);
		save();
	}

	/** Reprend la position d'une ancienne sauvegarde, sans jamais écraser un cadrage réglé depuis. */
	public synchronized void seedFraming(int playerId, String imageId, int playerCount, Framing framing) {
		boolean absent = !data.framing().getOrDefault(playerId, Map.of()).getOrDefault(imageId, Map.of())
				.containsKey(playerCount);
		if (absent) {
			setFraming(playerId, imageId, playerCount, framing);
		}
	}

	public static void requireFramingKey(String imageId, int playerCount) {
		if (imageId == null || !FRAMING_IMAGE_ID.matcher(imageId).matches()) {
			throw new InvalidActionException("Image inconnue");
		}
		if (playerCount < MIN_PLAYERS || playerCount > MAX_PLAYERS) {
			throw new InvalidActionException("Disposition inconnue : " + playerCount + " joueurs");
		}
	}

	// --- Interne

	/** Le nom ne sert qu'à l'affichage : on garde le dernier élément d'un éventuel chemin, sans caractères de contrôle. */
	static String cleanName(String originalName) {
		String name = originalName == null ? "" : originalName;
		name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
		name = name.replaceAll("\\p{Cntrl}", "").strip();
		if (name.length() > MAX_NAME_LENGTH) {
			name = name.substring(0, MAX_NAME_LENGTH).strip();
		}
		return name.isEmpty() ? "Image" : name;
	}

	private void save() {
		try {
			store.save(data);
		}
		catch (IOException | RuntimeException e) {
			// Les données restent en mémoire ; seule la reprise après redémarrage est compromise
			log.error("Échec de la sauvegarde dans {}", store.file(), e);
		}
	}

	private static void deleteFile(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException e) {
			log.warn("Impossible de supprimer {}", file, e);
		}
	}

	private static PlayerArtData load(PlayerArtStore store) {
		try {
			var saved = store.load();
			if (saved.isPresent()) {
				log.info("Bibliothèque d'images restaurée depuis {}", store.file());
				return saved.get();
			}
		}
		catch (IOException | RuntimeException e) {
			log.warn("Sauvegarde {} illisible, bibliothèque vide", store.file(), e);
		}
		return new PlayerArtData(null, null);
	}
}
