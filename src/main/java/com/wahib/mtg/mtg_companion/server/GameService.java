package com.wahib.mtg.mtg_companion.server;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import com.wahib.mtg.mtg_companion.cards.CardService;
import com.wahib.mtg.mtg_companion.game.Game;
import com.wahib.mtg.mtg_companion.game.GameSettings;
import com.wahib.mtg.mtg_companion.game.GameSnapshot;
import com.wahib.mtg.mtg_companion.game.InvalidActionException;
import com.wahib.mtg.mtg_companion.game.Player;
import com.wahib.mtg.mtg_companion.userimages.Framing;
import com.wahib.mtg.mtg_companion.userimages.PlayerArtService;

/**
 * Source de vérité : la partie unique en mémoire.
 * <p>
 * Toutes les méthodes publiques sont synchronisées, et la diffusion se fait à l'intérieur du verrou :
 * les clients reçoivent ainsi les états dans l'ordre où ils ont été produits, et un client qui se
 * connecte ne peut pas rater une modification entre son état initial et la diffusion suivante.
 */
@Service
public class GameService {

	private static final Logger log = LoggerFactory.getLogger(GameService.class);

	private final GameStateStore store;
	private final GameBroadcaster broadcaster;
	private final CardService cardService;
	private final PlayerArtService art;
	private Game game;

	public GameService(GameStateStore store, GameBroadcaster broadcaster, CardService cardService,
			PlayerArtService art) {
		this.store = store;
		this.broadcaster = broadcaster;
		this.cardService = cardService;
		this.art = art;
		this.game = loadOrCreateGame();
		seedFramingsFromSavedBackgrounds();
	}

	public synchronized void connect(WebSocketSession session) {
		broadcaster.register(session);
		broadcaster.sendState(session, game.snapshot());
	}

	public synchronized void disconnect(WebSocketSession session) {
		broadcaster.unregister(session);
	}

	/**
	 * Applique l'action, sauvegarde et diffuse le nouvel état à tous les clients.
	 *
	 * @throws InvalidActionException si l'action est refusée ; l'état n'est alors pas modifié
	 */
	public synchronized void handle(ClientMessage message) {
		switch (message) {
			case ClientMessage.ChangeLife action -> game.changeLife(action.player(), action.delta());
			case ClientMessage.ChangeCommanderDamage action ->
					game.changeCommanderDamage(action.player(), action.fromPlayer(), action.delta());
			case ClientMessage.StartGame action -> {
				Game next = new Game(new GameSettings(action.format(), action.startingLife(), action.players()));
				next.keepBackgroundsOf(game);
				applyFramingsOfLayout(next);
				game = next;
			}
			case ClientMessage.Reset ignored -> game.reset();
			case ClientMessage.SetPlayerBackground action -> setBackground(action);
			case ClientMessage.ClearPlayerBackground action -> game.setBackground(action.player(), null);
		}
		GameSnapshot snapshot = game.snapshot();
		save(snapshot);
		broadcaster.broadcastState(snapshot);
	}

	/**
	 * Retire une image de la bibliothèque et le fond des joueurs qui l'utilisent (ils retrouvent le fond par
	 * défaut ; leurs autres préférences sont conservées).
	 *
	 * @return faux si l'image n'existe pas
	 */
	public synchronized boolean deleteUserImage(String imageId) {
		if (!art.delete(imageId)) {
			return false;
		}
		boolean changed = false;
		for (Player player : game.players()) {
			GameSnapshot.Background background = player.background();
			if (background != null && GameSnapshot.Background.UPLOAD.equals(background.kind())
					&& imageId.equals(background.imageId())) {
				game.setBackground(player.id(), null);
				changed = true;
			}
		}
		if (changed) {
			GameSnapshot snapshot = game.snapshot();
			save(snapshot);
			broadcaster.broadcastState(snapshot);
		}
		return true;
	}

	public synchronized GameSnapshot snapshot() {
		return game.snapshot();
	}

	/**
	 * Valide tout avant de modifier quoi que ce soit : une action refusée ne change ni le fond ni les
	 * cadrages. La position affichée est celle de la disposition de la partie en cours.
	 */
	private void setBackground(ClientMessage.SetPlayerBackground action) {
		int playerCount = game.settings().playerCount();
		game.player(action.player());
		GameSnapshot.Background chosen = chosenBackground(action);
		Map<Integer, Framing> framings = action.framings() == null ? Map.of() : action.framings();
		framings.keySet().forEach(count -> PlayerArtService.requireFramingKey(chosen.imageId(), count));
		framings.forEach((count, framing) -> {
			if (framing == null) {
				throw new InvalidActionException("Cadrage invalide");
			}
		});

		framings.forEach((count, framing) -> art.setFraming(action.player(), chosen.imageId(), count, framing));
		Framing shown = art.framing(action.player(), chosen.imageId(), playerCount);
		game.setBackground(action.player(), chosen.withPosition(shown.x(), shown.y()));
	}

	/** L'image doit déjà être dans le cache (le téléphone l'a demandée avant) ou la bibliothèque : aucun appel réseau ici. */
	private GameSnapshot.Background chosenBackground(ClientMessage.SetPlayerBackground action) {
		if (GameSnapshot.Background.UPLOAD.equals(action.kind())) {
			if (!PlayerArtService.isValidImageId(action.imageId()) || art.find(action.imageId()).isEmpty()) {
				throw new InvalidActionException("Image inconnue, elle a peut-être été supprimée");
			}
			return GameSnapshot.Background.upload(action.imageId(), 50, 50);
		}
		if (action.kind() != null && !GameSnapshot.Background.SCRYFALL.equals(action.kind())) {
			throw new InvalidActionException("Type de fond inconnu");
		}
		int face = action.face() == null ? 0 : action.face();
		CardService.CachedArt cached = cardService.cachedArt(action.cardName(), action.printId(), face)
				.orElseThrow(() -> new InvalidActionException("Illustration inconnue, choisissez-la de nouveau"));
		return GameSnapshot.Background.scryfall(cached.cardName(), action.printId(), face, cached.artist(), 50, 50);
	}

	/** Nouvelle partie : chaque fond prend le cadrage que son joueur a réglé pour la nouvelle disposition. */
	private void applyFramingsOfLayout(Game next) {
		int playerCount = next.settings().playerCount();
		for (Player player : next.players()) {
			GameSnapshot.Background background = player.background();
			if (background != null) {
				Framing framing = art.framing(player.id(), background.imageId(), playerCount);
				next.setBackground(player.id(), background.withPosition(framing.x(), framing.y()));
			}
		}
	}

	/**
	 * Sauvegardes d'avant les cadrages par disposition : la position du fond devient le cadrage de la
	 * disposition en cours, sans écraser un cadrage déjà réglé.
	 */
	private void seedFramingsFromSavedBackgrounds() {
		int playerCount = game.settings().playerCount();
		for (Player player : game.players()) {
			GameSnapshot.Background background = player.background();
			if (background != null) {
				art.seedFraming(player.id(), background.imageId(), playerCount,
						new Framing(background.positionX(), background.positionY()));
			}
		}
	}

	private Game loadOrCreateGame() {
		try {
			var saved = store.load();
			if (saved.isPresent()) {
				log.info("Partie restaurée depuis {}", store.stateFile());
				return Game.restore(saved.get());
			}
			log.info("Aucune sauvegarde dans {}, nouvelle partie par défaut", store.stateFile());
		}
		catch (IOException | RuntimeException e) {
			log.warn("Sauvegarde {} illisible, nouvelle partie par défaut", store.stateFile(), e);
		}
		return new Game(GameSettings.defaults());
	}

	private void save(GameSnapshot snapshot) {
		try {
			store.save(snapshot);
		}
		catch (IOException | RuntimeException e) {
			// La partie continue en mémoire ; seule la reprise après redémarrage est compromise
			log.error("Échec de la sauvegarde dans {}", store.stateFile(), e);
		}
	}
}
