package com.wahib.mtg.mtg_companion.cards;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recherche et affichage de cartes depuis les téléphones. Les erreurs sont renvoyées au seul
 * téléphone qui a fait la demande, avec un message lisible ; la partie n'est jamais bloquée.
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

	public record SuggestionsResponse(List<CardSuggestions.Suggestion> suggestions, String warning) {
	}

	/**
	 * @param printId impression choisie, ou absent pour le choix automatique
	 * @param lang    {@code fr}, {@code en} ou {@code both} ; absent : {@code fr}
	 */
	public record ShowCardRequest(String name, String printId, String lang) {
	}

	/** @param warnings langues qui n'ont pas pu être affichées, à montrer au joueur ; vide si tout s'est bien passé */
	public record ShowCardResponse(List<String> warnings) {
	}

	public record IllustrationsResponse(String name, List<IllustrationView> illustrations) {
	}

	/**
	 * @param setName         édition, absente pour la liste de secours hors connexion
	 * @param collectorNumber numéro de collection dans l'édition
	 * @param thumbnail       vignette servie par ce serveur
	 * @param hasArt          vrai si l'impression a une illustration seule utilisable comme fond de zone
	 */
	public record IllustrationView(String printId, String lang, String setName, String collectorNumber,
			String thumbnail, boolean hasArt) {
	}

	/** @param image illustration seule, servie par ce serveur depuis son cache */
	public record ArtView(int face, String faceName, String artist, String image) {
	}

	public record ArtsResponse(String name, String printId, List<ArtView> arts) {
	}

	/** @param stale vrai si Scryfall était injoignable : ces rulings viennent d'un cache périmé */
	public record RulingsResponse(List<Ruling> rulings, boolean stale) {
	}

	public record ErrorResponse(String message) {
	}

	private static final String THUMBNAIL_URL_PREFIX = "/api/cards/thumbnails/";
	private static final String ART_URL_PREFIX = "/api/cards/arts/";
	private static final Set<String> SHOW_LANGUAGES = Set.of("fr", "en", "both");

	private final CardService cardService;
	private final CardDisplayService displayService;
	private final CardCache cache;

	public CardController(CardService cardService, CardDisplayService displayService, CardCache cache) {
		this.cardService = cardService;
		this.displayService = displayService;
		this.cache = cache;
	}

	@GetMapping("/suggestions")
	public SuggestionsResponse suggestions(@RequestParam(name = "q", defaultValue = "") String query) {
		CardService.SuggestionResult result = cardService.suggest(query);
		return new SuggestionsResponse(result.suggestions(), result.warning());
	}

	@GetMapping("/illustrations")
	public ResponseEntity<IllustrationsResponse> illustrations(@RequestParam(name = "name", defaultValue = "") String name) {
		if (name.isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		CardIllustrations illustrations = cardService.illustrations(name.strip());
		List<IllustrationView> views = illustrations.illustrations().stream()
				.map(illustration -> {
					// Vignette disponible si Scryfall en fournit une, ou si elle est déjà en cache
					boolean hasThumbnail = illustration.thumbnailUrl() != null
							|| cache.thumbnailPath(illustration.printId()).isPresent();
					return new IllustrationView(illustration.printId(), illustration.lang(), illustration.setName(),
							illustration.collectorNumber(),
							hasThumbnail ? THUMBNAIL_URL_PREFIX + illustration.printId() + ".jpg" : null,
								illustration.hasArt());
				})
				.toList();
		return ResponseEntity.ok(new IllustrationsResponse(illustrations.name(), views));
	}

	/**
	 * Illustrations seules d'une impression, pour le fond d'une zone de joueur. Elles sont téléchargées
	 * dans le cache ici, avant que le choix soit envoyé à la table.
	 */
	@GetMapping("/arts")
	public ResponseEntity<ArtsResponse> arts(@RequestParam(name = "name", defaultValue = "") String name,
			@RequestParam(name = "printId", required = false) String printId) {
		if (name.isBlank() || (printId != null && !CardCache.isValidPrintId(printId))) {
			return ResponseEntity.badRequest().build();
		}
		return cardService.artworks(name.strip(), printId)
				.map(found -> {
					List<ArtView> arts = found.print().arts().stream()
							.map(art -> new ArtView(art.face(), art.faceName(), art.artist(),
									ART_URL_PREFIX + CardCache.artFileName(found.print().printId(), art.face())))
							.toList();
					return ResponseEntity.ok(new ArtsResponse(found.cardName(), found.print().printId(), arts));
				})
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/arts/{fileName}")
	public ResponseEntity<Resource> art(@PathVariable String fileName) {
		return cache.artPath(fileName)
				.map(CardController::imageResponse)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** Rulings d'une impression, pour la consultation sur le téléphone ; rien n'est envoyé à la table. */
	@GetMapping("/rulings")
	public ResponseEntity<RulingsResponse> rulings(@RequestParam(name = "printId", defaultValue = "") String printId) {
		if (!CardCache.isValidPrintId(printId)) {
			return ResponseEntity.badRequest().build();
		}
		CardService.RulingsResult result = cardService.rulings(printId);
		return ResponseEntity.ok(new RulingsResponse(result.rulings(), result.stale()));
	}

	@PostMapping("/show")
	public ResponseEntity<ShowCardResponse> show(@RequestBody ShowCardRequest request) {
		boolean invalidPrintId = request.printId() != null && !CardCache.isValidPrintId(request.printId());
		if (request.name() == null || request.name().isBlank() || invalidPrintId) {
			return ResponseEntity.badRequest().build();
		}
		String lang = request.lang() == null ? "fr" : request.lang();
		if (!SHOW_LANGUAGES.contains(lang)) {
			return ResponseEntity.badRequest().build();
		}
		CardDisplay card = cardService.resolve(request.name().strip(), request.printId(), lang);
		displayService.show(card);
		return ResponseEntity.ok(new ShowCardResponse(card.warnings()));
	}

	/** Vignettes pour le choix de l'illustration, téléchargées chez Scryfall au premier affichage. */
	@GetMapping("/thumbnails/{fileName}")
	public ResponseEntity<Resource> thumbnail(@PathVariable String fileName) {
		if (!fileName.endsWith(".jpg")) {
			return ResponseEntity.notFound().build();
		}
		String printId = fileName.substring(0, fileName.length() - ".jpg".length());
		if (!CardCache.isValidPrintId(printId)) {
			return ResponseEntity.notFound().build();
		}
		return cardService.thumbnail(printId)
				.map(CardController::imageResponse)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PostMapping("/hide")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void hide() {
		displayService.hide();
	}

	/** Images servies depuis le cache : les navigateurs n'appellent jamais Scryfall. */
	@GetMapping("/images/{fileName}")
	public ResponseEntity<Resource> image(@PathVariable String fileName) {
		return cache.imagePath(fileName)
				.map(CardController::imageResponse)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@ExceptionHandler(CardNotFoundException.class)
	public ResponseEntity<ErrorResponse> cardNotFound(CardNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(ScryfallUnavailableException.class)
	public ResponseEntity<ErrorResponse> scryfallUnavailable(ScryfallUnavailableException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(e.getMessage()));
	}

	private static ResponseEntity<Resource> imageResponse(Path file) {
		// Une image est identifiée par l'identifiant Scryfall de l'impression : son contenu ne change pas
		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_JPEG)
				.cacheControl(CacheControl.maxAge(Duration.ofDays(30)))
				.body(new FileSystemResource(file));
	}
}
