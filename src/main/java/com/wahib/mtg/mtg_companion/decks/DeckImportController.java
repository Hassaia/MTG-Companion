package com.wahib.mtg.mtg_companion.decks;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wahib.mtg.mtg_companion.cards.ScryfallUnavailableException;
import com.wahib.mtg.mtg_companion.decks.DeckImportService.Draft;
import com.wahib.mtg.mtg_companion.decks.DeckImportService.DuplicateDeckNameException;
import com.wahib.mtg.mtg_companion.decks.DeckImportService.SaveRequest;
import com.wahib.mtg.mtg_companion.server.GameService;

/** Import, renommage et suppression des decks importés ; leur consultation passe par {@link DeckController}. */
@RestController
@RequestMapping("/api/decks/custom")
public class DeckImportController {

	public record PreviewRequest(String text) {
	}

	public record RenameRequest(String name) {
	}

	public record ErrorResponse(String message) {
	}

	/** @param existingId deck déjà nommé ainsi : le client propose de le mettre à jour, de renommer ou d'annuler */
	public record DuplicateResponse(String message, String existingId, String existingName) {
	}

	private final DeckImportService importService;
	private final GameService gameService;

	public DeckImportController(DeckImportService importService, GameService gameService) {
		this.importService = importService;
		this.gameService = gameService;
	}

	@PostMapping("/preview")
	public Draft preview(@RequestBody PreviewRequest request) {
		return importService.preview(request.text());
	}

	@PostMapping
	public DeckSummary save(@RequestBody SaveRequest request) {
		return summary(importService.save(request));
	}

	@PatchMapping("/{id}")
	public ResponseEntity<DeckSummary> rename(@PathVariable String id, @RequestBody RenameRequest request) {
		if (!DeckService.isValidId(id)) {
			return ResponseEntity.badRequest().build();
		}
		return ResponseEntity.ok(summary(importService.rename(id, request.name())));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		if (!DeckService.isValidId(id)) {
			return ResponseEntity.badRequest().build();
		}
		return gameService.deleteCustomDeck(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	private static DeckSummary summary(DeckContent deck) {
		return new DeckSummary(deck.id(), deck.name(), null, null, deck.releaseDate(),
				deck.commanders().stream().map(DeckContent.DeckCard::name).toList(), DeckOrigin.CUSTOM);
	}

	@ExceptionHandler(DuplicateDeckNameException.class)
	public ResponseEntity<DuplicateResponse> duplicate(DuplicateDeckNameException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new DuplicateResponse(e.getMessage(), e.existingId(), e.existingName()));
	}

	@ExceptionHandler(DeckImportService.DeckImportException.class)
	public ResponseEntity<ErrorResponse> invalid(DeckImportService.DeckImportException e) {
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(DeckNotFoundException.class)
	public ResponseEntity<ErrorResponse> notFound(DeckNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(ScryfallUnavailableException.class)
	public ResponseEntity<ErrorResponse> scryfallUnavailable(ScryfallUnavailableException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(e.getMessage()));
	}
}
