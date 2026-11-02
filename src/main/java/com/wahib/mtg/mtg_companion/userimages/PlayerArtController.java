package com.wahib.mtg.mtg_companion.userimages;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wahib.mtg.mtg_companion.server.GameService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Bibliothèque d'images partagée entre les téléphones, et cadrages de chaque joueur. L'image est envoyée
 * telle quelle dans le corps de la requête (pas de formulaire) : la taille est donc bornée ici, octet par octet.
 */
@RestController
@RequestMapping("/api")
public class PlayerArtController {

	/** @param url adresse de l'image, servie par ce serveur */
	public record UserImageView(String id, String name, int width, int height, long bytes, String url) {
	}

	public record ErrorResponse(String message) {
	}

	private static final String IMAGE_URL_PREFIX = "/api/user-images/";

	private final PlayerArtService art;
	private final GameService gameService;

	public PlayerArtController(PlayerArtService art, GameService gameService) {
		this.art = art;
		this.gameService = gameService;
	}

	@GetMapping("/user-images")
	public List<UserImageView> list() {
		return art.list().stream().map(PlayerArtController::view).toList();
	}

	@PostMapping("/user-images")
	public ResponseEntity<UserImageView> upload(@RequestParam(name = "name", defaultValue = "") String name,
			HttpServletRequest request) throws IOException {
		// Un octet de plus que le maximum suffit à savoir que le fichier est trop gros, sans le lire en entier
		byte[] content = request.getInputStream().readNBytes((int) PlayerArtService.MAX_BYTES + 1);
		UserImageView created = view(art.add(name, content));
		return ResponseEntity.created(URI.create(created.url())).body(created);
	}

	/** Servie avec le type détecté à l'import, jamais celui que demanderait le navigateur. */
	@GetMapping("/user-images/{id}")
	public ResponseEntity<Resource> image(@PathVariable String id) {
		if (!PlayerArtService.isValidImageId(id)) {
			return ResponseEntity.notFound().build();
		}
		UserImage image = art.find(id).orElse(null);
		Path file = art.file(id).orElse(null);
		if (image == null || file == null) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(image.contentType()))
				.header("X-Content-Type-Options", "nosniff")
				// L'identifiant est unique : le contenu d'une image ne change jamais
				.cacheControl(CacheControl.maxAge(Duration.ofDays(30)).immutable())
				.body(new FileSystemResource(file));
	}

	/** Retire aussi le fond des joueurs qui l'utilisent : ils retrouvent le fond par défaut. */
	@DeleteMapping("/user-images/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		if (!PlayerArtService.isValidImageId(id) || !gameService.deleteUserImage(id)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/player-art/{playerId}/framing")
	public Map<String, Map<Integer, Framing>> framing(@PathVariable int playerId) {
		return art.framingsOf(playerId);
	}

	@ExceptionHandler(UserImageException.class)
	public ResponseEntity<ErrorResponse> refused(UserImageException e) {
		return ResponseEntity.status(HttpStatus.valueOf(e.httpStatus())).body(new ErrorResponse(e.getMessage()));
	}

	private static UserImageView view(UserImage image) {
		return new UserImageView(image.id(), image.name(), image.width(), image.height(), image.bytes(),
				IMAGE_URL_PREFIX + image.id());
	}
}
