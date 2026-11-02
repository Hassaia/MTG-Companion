package com.wahib.mtg.mtg_companion.cards;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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

	public record ShowCardRequest(String name) {
	}

	public record ErrorResponse(String message) {
	}

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

	@PostMapping("/show")
	public ResponseEntity<Void> show(@RequestBody ShowCardRequest request) {
		if (request.name() == null || request.name().isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		displayService.show(cardService.resolve(request.name().strip()));
		return ResponseEntity.noContent().build();
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
		// Une image est identifiée par l'id Scryfall de l'impression : son contenu ne change pas
		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_JPEG)
				.cacheControl(CacheControl.maxAge(Duration.ofDays(30)))
				.body(new FileSystemResource(file));
	}
}
