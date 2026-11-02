package com.wahib.mtg.mtg_companion.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Horloge simulée : chaque attente demandée fait avancer le temps, sans vraiment dormir. */
class RequestThrottleTest {

	private long now = 1_000_000_000L;
	private final List<Duration> sleeps = new ArrayList<>();
	private final RequestThrottle throttle = new RequestThrottle(() -> now, nanos -> {
		sleeps.add(Duration.ofNanos(nanos));
		now += nanos;
	});

	@Test
	void firstRequestDoesNotWait() throws Exception {
		throttle.acquire(throttle.lane(Duration.ofMillis(500)));

		assertThat(sleeps).isEmpty();
	}

	@Test
	void waitsForTheRestOfTheInterval() throws Exception {
		RequestThrottle.Lane lane = throttle.lane(Duration.ofMillis(500));
		throttle.acquire(lane);
		now += Duration.ofMillis(200).toNanos();

		throttle.acquire(lane);

		assertThat(sleeps).containsExactly(Duration.ofMillis(300));
	}

	@Test
	void doesNotWaitWhenTheIntervalHasPassed() throws Exception {
		RequestThrottle.Lane lane = throttle.lane(Duration.ofMillis(500));
		throttle.acquire(lane);
		now += Duration.ofMillis(800).toNanos();

		throttle.acquire(lane);

		assertThat(sleeps).isEmpty();
	}

	@Test
	void lanesAreIndependent() throws Exception {
		RequestThrottle.Lane search = throttle.lane(Duration.ofMillis(500));
		RequestThrottle.Lane general = throttle.lane(Duration.ofMillis(100));
		throttle.acquire(search);

		throttle.acquire(general);

		assertThat(sleeps).isEmpty();
	}

	@Test
	void pauseLastsForTheRequestedDuration() {
		throttle.pauseFor(Duration.ofSeconds(30));
		now += Duration.ofSeconds(10).toNanos();
		assertThat(throttle.remainingPause()).isEqualTo(Duration.ofSeconds(20));

		now += Duration.ofSeconds(20).toNanos();
		assertThat(throttle.remainingPause()).isZero();
	}
}
