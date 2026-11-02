package com.wahib.mtg.mtg_companion.cards;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import com.wahib.mtg.mtg_companion.server.GameBroadcaster;
import com.wahib.mtg.mtg_companion.server.ServerMessage;

import jakarta.annotation.PreDestroy;

/**
 * La carte affichée en ce moment sur la table, partagée par tous les écrans. Elle se ferme
 * d'elle-même après un délai ; une nouvelle carte remplace la précédente et relance ce délai.
 * Elle n'est pas sauvegardée : après un redémarrage, aucune carte n'est affichée.
 */
@Service
public class CardDisplayService {

	public static final String IMAGE_URL_PREFIX = "/api/cards/images/";

	/** @param images adresses des images, servies par ce serveur et non par Scryfall */
	public record VariantView(String lang, List<String> images) {
	}

	public record CardView(String name, List<VariantView> variants, List<String> warnings) {
	}

	private record DisplayedCard(CardView card, long hideAtNanos) {
	}

	private final GameBroadcaster broadcaster;
	private final Duration displayDuration;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "card-auto-hide");
		thread.setDaemon(true);
		return thread;
	});

	private DisplayedCard current;
	private ScheduledFuture<?> hideTask;

	public CardDisplayService(GameBroadcaster broadcaster,
			@Value("${mtg.card-display-duration}") Duration displayDuration) {
		this.broadcaster = broadcaster;
		this.displayDuration = displayDuration;
	}

	public synchronized void show(CardDisplay card) {
		cancelHideTask();
		DisplayedCard displayed = new DisplayedCard(toView(card), System.nanoTime() + displayDuration.toNanos());
		current = displayed;
		hideTask = scheduler.schedule(() -> hideIfStillShown(displayed), displayDuration.toMillis(), TimeUnit.MILLISECONDS);
		broadcaster.broadcast(shownMessage(displayed));
	}

	public synchronized void hide() {
		if (current == null) {
			return;
		}
		cancelHideTask();
		current = null;
		broadcaster.broadcast(new ServerMessage.CardHidden());
	}

	/** Pour un écran qui se connecte pendant qu'une carte est affichée. */
	public synchronized void sendCurrentTo(WebSocketSession session) {
		if (current != null) {
			broadcaster.send(session, shownMessage(current));
		}
	}

	@PreDestroy
	void shutdown() {
		scheduler.shutdownNow();
	}

	private synchronized void hideIfStillShown(DisplayedCard displayed) {
		// Une autre carte a pu être affichée entre-temps : seule la carte d'origine est fermée
		if (current == displayed) {
			hide();
		}
	}

	private ServerMessage.CardShown shownMessage(DisplayedCard displayed) {
		// Durée restante plutôt qu'une heure absolue : les horloges des téléphones ne sont pas celles du serveur
		long remainingMs = Math.max(0, (displayed.hideAtNanos() - System.nanoTime()) / 1_000_000);
		return new ServerMessage.CardShown(displayed.card(), remainingMs, displayDuration.toMillis());
	}

	private void cancelHideTask() {
		if (hideTask != null) {
			hideTask.cancel(false);
			hideTask = null;
		}
	}

	private static CardView toView(CardDisplay card) {
		List<VariantView> variants = card.variants().stream()
				.map(variant -> new VariantView(variant.lang(),
						variant.images().stream().map(image -> IMAGE_URL_PREFIX + image.fileName()).toList()))
				.toList();
		return new CardView(card.name(), variants, card.warnings());
	}
}
