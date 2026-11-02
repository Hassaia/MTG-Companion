package com.wahib.mtg.mtg_companion.server;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import com.wahib.mtg.mtg_companion.game.Game;
import com.wahib.mtg.mtg_companion.game.GameSettings;
import com.wahib.mtg.mtg_companion.game.GameSnapshot;
import com.wahib.mtg.mtg_companion.game.InvalidActionException;

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
	private Game game;

	public GameService(GameStateStore store, GameBroadcaster broadcaster) {
		this.store = store;
		this.broadcaster = broadcaster;
		this.game = loadOrCreateGame();
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
			case ClientMessage.StartGame action ->
					game = new Game(new GameSettings(action.format(), action.startingLife(), action.players()));
			case ClientMessage.Reset ignored -> game.reset();
		}
		GameSnapshot snapshot = game.snapshot();
		save(snapshot);
		broadcaster.broadcastState(snapshot);
	}

	public synchronized GameSnapshot snapshot() {
		return game.snapshot();
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
