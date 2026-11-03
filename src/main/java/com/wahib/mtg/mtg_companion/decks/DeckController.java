package com.wahib.mtg.mtg_companion.decks;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wahib.mtg.mtg_companion.game.GameSnapshot;
import com.wahib.mtg.mtg_companion.server.GameService;

/**
 * Catalogue des préconstruits, leurs cartes et les decks choisis par les joueurs de la partie.
 * La sélection elle-même passe par le WebSocket (elle fait partie de l'état de la partie).
 */
@RestController
@RequestMapping("/api/decks")
public class DeckController {

	/** @param commanders commandants connus ; vide si le deck n'a pas pu être consulté */
	public record InPlayDeck(int playerId, String playerName, String deckId, String deckName, List<String> commanders) {
	}

	public record ErrorResponse(String message) {
	}

	private final DeckService deckService;
	private final GameService gameService;

	public DeckController(DeckService deckService, GameService gameService) {
		this.deckService = deckService;
		this.gameService = gameService;
	}

	@GetMapping
	public List<DeckSummary> search(@RequestParam(name = "q", defaultValue = "") String query,
			@RequestParam(name = "origin", defaultValue = "MTGJSON") DeckOrigin origin) {
		return deckService.search(query, origin);
	}

	/** Un deck par joueur qui en a choisi un, dans l'ordre des joueurs ; ses cartes ne sont pas renvoyées ici. */
	@GetMapping("/in-play")
	public List<InPlayDeck> inPlay() {
		return gameService.snapshot().players().stream()
				.filter(player -> player.deckId() != null)
				.map(this::inPlayDeck)
				.toList();
	}

	@GetMapping("/{id}")
	public ResponseEntity<DeckContent> deck(@PathVariable String id) {
		if (!DeckService.isValidId(id)) {
			return ResponseEntity.badRequest().build();
		}
		return ResponseEntity.ok(deckService.deck(id));
	}

	private InPlayDeck inPlayDeck(GameSnapshot.PlayerSnapshot player) {
		String deckName = deckService.find(player.deckId()).map(DeckSummary::name).orElse(player.deckId());
		List<String> commanders;
		try {
			// Premier affichage du deck choisi : c'est ici qu'il est téléchargé, une seule fois
			commanders = deckService.deck(player.deckId()).commanders().stream()
					.map(DeckContent.DeckCard::name)
					.toList();
		}
		catch (DecksUnavailableException | DeckNotFoundException e) {
			commanders = deckService.knownCommanders(player.deckId());
		}
		return new InPlayDeck(player.id(), player.name(), player.deckId(), deckName, commanders);
	}

	@ExceptionHandler(DeckNotFoundException.class)
	public ResponseEntity<ErrorResponse> notFound(DeckNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
	}

	@ExceptionHandler(DecksUnavailableException.class)
	public ResponseEntity<ErrorResponse> unavailable(DecksUnavailableException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(e.getMessage()));
	}
}
