package com.wahib.mtg.mtg_companion.cards;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
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
 * card-cache/cards/&lt;empreinte du nom&gt;.json           carte avec son impression par défaut
 * card-cache/illustrations/&lt;empreinte du nom&gt;.json   illustrations distinctes de la carte
 * card-cache/prints/&lt;id impression&gt;.json             impression trouvée par édition et numéro
 * card-cache/variants/&lt;empreinte édition/n°/langue&gt;.json  version d'une impression dans une langue
 * card-cache/images/&lt;id impression&gt;-&lt;face&gt;.jpg      grandes images, pour la table
 * card-cache/arts/&lt;id impression&gt;-&lt;face&gt;.jpg        illustrations seules, pour le fond des zones de joueur
 * card-cache/thumbnails/&lt;id impression&gt;.jpg         vignettes, pour le choix sur le téléphone
 * card-cache/rulings/&lt;id impression&gt;.json           rulings avec leur date de récupération (périmés après 7 jours)
 * </pre>
 */
@Component
public class CardCache {

	private static final Logger log = LoggerFactory.getLogger(CardCache.class);
	/** Seuls noms acceptés pour une image : empêche toute lecture hors du dossier du cache. */
	private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
	private static final Pattern IMAGE_FILE_NAME = Pattern.compile(UUID + "-[0-9]\\.jpg");
	private static final Pattern PRINT_ID = Pattern.compile(UUID);

	private final Path cardsDirectory;
	private final Path illustrationsDirectory;
	private final Path printsDirectory;
	private final Path variantsDirectory;
	private final Path imagesDirectory;
	private final Path artsDirectory;
	private final Path thumbnailsDirectory;
	private final Path rulingsDirectory;
	private final JsonMapper jsonMapper;

	public CardCache(@Value("${mtg.card-cache-dir}") Path cacheDirectory, JsonMapper jsonMapper) {
		Path root = cacheDirectory.toAbsolutePath();
		this.cardsDirectory = root.resolve("cards");
		this.illustrationsDirectory = root.resolve("illustrations");
		this.printsDirectory = root.resolve("prints");
		this.variantsDirectory = root.resolve("variants");
		this.imagesDirectory = root.resolve("images");
		this.artsDirectory = root.resolve("arts");
		this.thumbnailsDirectory = root.resolve("thumbnails");
		this.rulingsDirectory = root.resolve("rulings");
		this.jsonMapper = jsonMapper;
	}

	public static boolean isValidImageFileName(String fileName) {
		return fileName != null && IMAGE_FILE_NAME.matcher(fileName).matches();
	}

	/** Identifiant Scryfall d'une impression (UUID en minuscules). */
	public static boolean isValidPrintId(String printId) {
		return printId != null && PRINT_ID.matcher(printId).matches();
	}

	/** @param name nom anglais de la carte, sans tenir compte de la casse */
	public Optional<Card> find(String name) {
		return read(nameFile(cardsDirectory, name), Card.class);
	}

	public void save(Card card) throws IOException {
		write(nameFile(cardsDirectory, card.name()), card);
	}

	public Optional<CardIllustrations> findIllustrations(String name) {
		return read(nameFile(illustrationsDirectory, name), CardIllustrations.class);
	}

	public void saveIllustrations(CardIllustrations illustrations) throws IOException {
		write(nameFile(illustrationsDirectory, illustrations.name()), illustrations);
	}

	/** Impression rangée avec le nom de sa carte, pour ne jamais afficher l'image d'une autre carte. */
	record CachedPrint(String cardName, CardIllustrations.Illustration print) {
	}

	/**
	 * Impression précise (trouvée par édition et numéro, ou par identifiant).
	 *
	 * @return vide si l'impression n'est pas en cache ou n'appartient pas à cette carte
	 */
	public Optional<CardIllustrations.Illustration> findPrint(String cardName, String printId) {
		if (!isValidPrintId(printId)) {
			return Optional.empty();
		}
		return read(printsDirectory.resolve(printId + ".json"), CachedPrint.class)
				.filter(cached -> cached.cardName().equalsIgnoreCase(cardName))
				.map(CachedPrint::print);
	}

	public void savePrint(String cardName, CardIllustrations.Illustration print) throws IOException {
		if (!isValidPrintId(print.printId())) {
			throw new IllegalArgumentException("Identifiant d'impression invalide : " + print.printId());
		}
		write(printsDirectory.resolve(print.printId() + ".json"), new CachedPrint(cardName, print));
	}

	/** Version d'une impression dans une langue, retrouvée par édition, numéro et langue. */
	public Optional<CardIllustrations.Illustration> findVariant(String cardName, String setCode, String collectorNumber, String lang) {
		return read(nameFile(variantsDirectory, variantKey(setCode, collectorNumber, lang)), CachedPrint.class)
				.filter(cached -> cached.cardName().equalsIgnoreCase(cardName))
				.map(CachedPrint::print);
	}

	public void saveVariant(String cardName, String setCode, String collectorNumber, String lang,
			CardIllustrations.Illustration print) throws IOException {
		write(nameFile(variantsDirectory, variantKey(setCode, collectorNumber, lang)), new CachedPrint(cardName, print));
	}

	private static String variantKey(String setCode, String collectorNumber, String lang) {
		return setCode + "/" + collectorNumber + "/" + lang;
	}

	/** Rulings d'une impression avec la date de leur récupération, pour décider s'ils sont périmés. */
	record CachedRulings(Instant fetchedAt, List<Ruling> rulings) {
	}

	/** @return les rulings en cache, même périmés ; vide si absents ou si l'identifiant n'est pas valide */
	public Optional<CachedRulings> findRulings(String printId) {
		if (!isValidPrintId(printId)) {
			return Optional.empty();
		}
		return read(rulingsDirectory.resolve(printId + ".json"), CachedRulings.class);
	}

	public void saveRulings(String printId, CachedRulings rulings) throws IOException {
		if (!isValidPrintId(printId)) {
			throw new IllegalArgumentException("Identifiant d'impression invalide : " + printId);
		}
		write(rulingsDirectory.resolve(printId + ".json"), rulings);
	}

	/** @return la vignette si elle est en cache ; vide si absente ou si l'identifiant n'est pas valide */
	public Optional<Path> thumbnailPath(String printId) {
		if (!isValidPrintId(printId)) {
			return Optional.empty();
		}
		Path file = thumbnailsDirectory.resolve(printId + ".jpg");
		return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
	}

	public void saveThumbnail(String printId, byte[] content) throws IOException {
		if (!isValidPrintId(printId)) {
			throw new IllegalArgumentException("Identifiant d'impression invalide : " + printId);
		}
		AtomicFiles.write(thumbnailsDirectory.resolve(printId + ".jpg"), content);
	}

	/** Toutes les cartes du cache, pour proposer des suggestions quand Scryfall est injoignable. */
	public List<Card> all() {
		List<Card> cards = new ArrayList<>();
		if (!Files.isDirectory(cardsDirectory)) {
			return cards;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(cardsDirectory, "*.json")) {
			files.forEach(file -> read(file, Card.class).ifPresent(cards::add));
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

	/** @return l'illustration seule si elle est en cache ; vide si absente ou si le nom n'est pas valide */
	public Optional<Path> artPath(String fileName) {
		if (!isValidImageFileName(fileName)) {
			return Optional.empty();
		}
		Path file = artsDirectory.resolve(fileName);
		return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
	}

	public void saveArt(String fileName, byte[] content) throws IOException {
		if (!isValidImageFileName(fileName)) {
			throw new IllegalArgumentException("Nom d'image invalide : " + fileName);
		}
		AtomicFiles.write(artsDirectory.resolve(fileName), content);
	}

	/** Nom du fichier d'une illustration seule, de la même forme que celui d'une grande image. */
	public static String artFileName(String printId, int face) {
		return printId + "-" + face + ".jpg";
	}

	private <T> Optional<T> read(Path file, Class<T> type) {
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		try {
			return Optional.of(jsonMapper.readValue(Files.readString(file), type));
		}
		catch (IOException | JacksonException e) {
			log.warn("Fichier du cache illisible, ignoré : {}", file, e);
			return Optional.empty();
		}
	}

	private void write(Path file, Object content) throws IOException {
		AtomicFiles.write(file, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(content));
	}

	/** Le nom d'une carte peut contenir n'importe quel caractère : le fichier est nommé d'après son empreinte. */
	private static Path nameFile(Path directory, String name) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
			return directory.resolve(HexFormat.of().formatHex(digest) + ".json");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 est toujours disponible dans le JDK", e);
		}
	}
}
