package com.wahib.mtg.mtg_companion.server;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.wahib.mtg.mtg_companion.game.GameSnapshot;

import tools.jackson.databind.json.JsonMapper;

/** Tient la liste des navigateurs connectés et leur envoie les messages du serveur. */
@Component
public class GameBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(GameBroadcaster.class);
	private static final int SEND_TIME_LIMIT_MS = 5_000;
	private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

	private final JsonMapper jsonMapper;
	private final ServerAddressResolver addressResolver;
	/** Une WebSocketSession n'accepte pas d'envois concurrents : le décorateur les met en file. */
	private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

	public GameBroadcaster(JsonMapper jsonMapper, ServerAddressResolver addressResolver) {
		this.jsonMapper = jsonMapper;
		this.addressResolver = addressResolver;
	}

	public void register(WebSocketSession session) {
		sessions.put(session.getId(),
				new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
	}

	public void unregister(WebSocketSession session) {
		sessions.remove(session.getId());
	}

	public void sendState(WebSocketSession session, GameSnapshot snapshot) {
		send(session.getId(), toJson(new ServerMessage.State(addressResolver.resolve(), snapshot)));
	}

	public void broadcastState(GameSnapshot snapshot) {
		String json = toJson(new ServerMessage.State(addressResolver.resolve(), snapshot));
		sessions.keySet().forEach(sessionId -> send(sessionId, json));
	}

	public void sendError(WebSocketSession session, String message) {
		send(session.getId(), toJson(new ServerMessage.Error(message)));
	}

	private String toJson(Object message) {
		return jsonMapper.writeValueAsString(message);
	}

	private void send(String sessionId, String json) {
		WebSocketSession session = sessions.get(sessionId);
		if (session == null || !session.isOpen()) {
			sessions.remove(sessionId);
			return;
		}
		try {
			session.sendMessage(new TextMessage(json));
		}
		catch (IOException | RuntimeException e) {
			// Client injoignable (téléphone en veille…) : il recevra l'état complet à sa reconnexion
			log.debug("Envoi impossible à la session {}, fermeture", sessionId, e);
			sessions.remove(sessionId);
			closeQuietly(session);
		}
	}

	private static void closeQuietly(WebSocketSession session) {
		try {
			session.close(CloseStatus.SESSION_NOT_RELIABLE);
		}
		catch (IOException ignored) {
			// déjà fermée
		}
	}
}
