package com.wahib.mtg.mtg_companion.cards;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Fait respecter les délais imposés par Scryfall entre deux requêtes, et la pause exigée
 * après une réponse HTTP 429.
 * <p>
 * Chaque « voie » correspond à une famille de requêtes soumise à sa propre limite. Les appels
 * concurrents sur une même voie sont mis en file : chacun attend son tour.
 */
public class RequestThrottle {

	@FunctionalInterface
	interface Sleeper {
		void sleep(long nanos) throws InterruptedException;
	}

	private final LongSupplier nanoClock;
	private final Sleeper sleeper;
	private final Object pauseLock = new Object();
	private boolean paused;
	private long pausedUntil;

	public RequestThrottle() {
		this(System::nanoTime, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
	}

	RequestThrottle(LongSupplier nanoClock, Sleeper sleeper) {
		this.nanoClock = nanoClock;
		this.sleeper = sleeper;
	}

	public Lane lane(Duration minimumInterval) {
		return new Lane(minimumInterval.toNanos());
	}

	/** Attend, si nécessaire, que le délai minimal depuis la requête précédente de cette voie soit écoulé. */
	public void acquire(Lane lane) throws InterruptedException {
		synchronized (lane) {
			long now = nanoClock.getAsLong();
			if (lane.hasPreviousRequest) {
				long wait = lane.previousRequestStart + lane.minimumIntervalNanos - now;
				if (wait > 0) {
					sleeper.sleep(wait);
					now += wait;
				}
			}
			lane.previousRequestStart = now;
			lane.hasPreviousRequest = true;
		}
	}

	public void pauseFor(Duration duration) {
		synchronized (pauseLock) {
			paused = true;
			pausedUntil = nanoClock.getAsLong() + duration.toNanos();
		}
	}

	/** @return le temps restant avant de pouvoir rappeler Scryfall, ou zéro */
	public Duration remainingPause() {
		synchronized (pauseLock) {
			if (!paused) {
				return Duration.ZERO;
			}
			long remaining = pausedUntil - nanoClock.getAsLong();
			if (remaining <= 0) {
				paused = false;
				return Duration.ZERO;
			}
			return Duration.ofNanos(remaining);
		}
	}

	public static final class Lane {

		private final long minimumIntervalNanos;
		private boolean hasPreviousRequest;
		private long previousRequestStart;

		private Lane(long minimumIntervalNanos) {
			this.minimumIntervalNanos = minimumIntervalNanos;
		}
	}
}
