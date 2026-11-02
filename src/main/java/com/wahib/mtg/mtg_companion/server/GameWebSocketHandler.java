package com.wahib.mtg.mtg_companion.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.wahib.mtg.mtg_companion.game.InvalidActionException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Point d'entrée WebSocket : traduit le JSON reçu en {@link ClientMessage} et le confie au {@link GameService}. */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

	private final GameService gameService;
	private final GameBroadcaster broadcaster;
	private final JsonMapper jsonMapper;

	public GameWebSocketHandler(GameService gameService, GameBroadcaster broadcaster, JsonMapper jsonMapper) {
		this.gameService = gameService;
		this.broadcaster = broadcaster;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		gameService.connect(session);
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
		ClientMessage message;
		try {
			message = jsonMapper.readValue(textMessage.getPayload(), ClientMessage.class);
		}
		catch (JacksonException e) {
			log.debug("Message illisible : {}", textMessage.getPayload(), e);
			broadcaster.sendError(session, "Message invalide");
			return;
		}
		try {
			gameService.handle(message);
		}
		catch (InvalidActionException e) {
			broadcaster.sendError(session, e.getMessage());
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.debug("Erreur de transport sur la session {}", session.getId(), exception);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		gameService.disconnect(session);
	}
}
