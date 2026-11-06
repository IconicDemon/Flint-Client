package com.flintclient.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.exceptions.MinecraftClientHttpException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class JoinRetryTest {
	/** Throws a checked exception from a lambda that can't declare it, as authlib's join does through Mixin. */
	@SuppressWarnings("unchecked")
	private static <T extends Throwable> void sneaky(Throwable t) throws T {
		throw (T) t;
	}

	/** authlib's own mapping: a 5xx answer is "unavailable", a 429 a plain refusal, both with the HTTP exception as cause. */
	private static AuthenticationException answered(int status) {
		MinecraftClientHttpException http = new MinecraftClientHttpException(status);
		return status >= 500 ? new AuthenticationUnavailableException(http.getMessage(), http) : new AuthenticationException(http.getMessage(), http);
	}

	/** A clock that moves only when the retry pauses, and the pauses it took. */
	private static final class Fake {
		final AtomicLong now = new AtomicLong(1_000_000);
		final List<Long> pauses = new ArrayList<>();

		void run(JoinRetry.Join join) {
			JoinRetry.run(join, ms -> {
				pauses.add(ms);
				now.addAndGet(ms);
			}, now::get);
		}
	}

	@Test
	void aBurstOf503sIsWaitedOutSecondsApartAndTheJoinGoesThrough() {
		Fake fake = new Fake();
		AtomicInteger tries = new AtomicInteger();
		fake.run(() -> {
			if (tries.incrementAndGet() <= 3) {
				JoinRetryTest.<RuntimeException>sneaky(answered(503));
			}
		});
		assertEquals(4, tries.get());
		assertEquals(List.of(1_000L, 2_000L, 3_500L), fake.pauses, "seconds apart, not a burst of tries inside the same second");
	}

	@Test
	void mojangLimitingJoinsIsWaitedOutToo() {
		Fake fake = new Fake();
		AtomicInteger tries = new AtomicInteger();
		fake.run(() -> {
			if (tries.incrementAndGet() == 1) {
				JoinRetryTest.<RuntimeException>sneaky(answered(429));
			}
		});
		assertEquals(2, tries.get());
	}

	@Test
	void anUnreachableServerIsTriedAgain() {
		Fake fake = new Fake();
		AtomicInteger tries = new AtomicInteger();
		fake.run(() -> {
			if (tries.incrementAndGet() == 1) {
				JoinRetryTest.<RuntimeException>sneaky(new AuthenticationUnavailableException(new SocketTimeoutException("connect timed out")));
			}
		});
		assertEquals(2, tries.get());
	}

	@Test
	void aServerThatStaysDownIsReportedAfterFiveTriesWithinTheLoginTime() {
		Fake fake = new Fake();
		AtomicInteger tries = new AtomicInteger();
		AuthenticationException down = answered(503);
		Throwable thrown = assertThrows(AuthenticationUnavailableException.class, () -> fake.run(() -> {
			tries.incrementAndGet();
			JoinRetryTest.<RuntimeException>sneaky(down);
		}));
		assertSame(down, thrown, "the player sees Minecraft's own message");
		assertEquals(JoinRetry.ATTEMPTS, tries.get());
		assertEquals(5, JoinRetry.ATTEMPTS);
		long waited = fake.pauses.stream().mapToLong(Long::longValue).sum();
		assertTrue(waited <= JoinRetry.BUDGET_MS && JoinRetry.BUDGET_MS < 30_000, "well inside a server's 30 s login wait: " + waited);
	}

	@Test
	void slowFailuresStopRetryingBeforeTheServerGivesUpOnTheLogin() {
		Fake fake = new Fake();
		AtomicInteger tries = new AtomicInteger();
		// Each try takes 6 s to fail (a connection that times out): no new try may start past the budget.
		assertThrows(AuthenticationUnavailableException.class, () -> fake.run(() -> {
			tries.incrementAndGet();
			fake.now.addAndGet(6_000);
			JoinRetryTest.<RuntimeException>sneaky(new AuthenticationUnavailableException("timed out"));
		}));
		assertEquals(3, tries.get(), "6 + 1 + 6 + 2 + 6 s, then a 3.5 s pause would pass the 16 s budget");
	}

	@Test
	void a429ThatNeverClearsShowsMinecraftsNotReachableMessage() {
		Fake fake = new Fake();
		Throwable thrown = assertThrows(AuthenticationUnavailableException.class, () -> fake.run(() -> JoinRetryTest.<RuntimeException>sneaky(answered(429))));
		assertInstanceOf(MinecraftClientHttpException.class, thrown.getCause().getCause());
	}

	@Test
	void aRefusedLoginIsReportedAtOnce() {
		Fake fake = new Fake();
		AtomicInteger tries = new AtomicInteger();
		assertThrows(InvalidCredentialsException.class, () -> fake.run(() -> {
			tries.incrementAndGet();
			JoinRetryTest.<RuntimeException>sneaky(new InvalidCredentialsException("Invalid session"));
		}));
		assertEquals(1, tries.get(), "an expired session isn't hammered");
		AtomicInteger other = new AtomicInteger();
		assertThrows(AuthenticationException.class, () -> fake.run(() -> {
			other.incrementAndGet();
			JoinRetryTest.<RuntimeException>sneaky(answered(403));
		}));
		assertEquals(1, other.get());
		assertTrue(fake.pauses.isEmpty());
	}

	@Test
	void theLogSaysWhatMojangAnsweredWithoutAnyAddress() {
		assertEquals("answered 503", JoinRetry.describe(answered(503)));
		assertEquals("couldn't be reached (SocketTimeoutException)", JoinRetry.describe(new AuthenticationUnavailableException("x", new RuntimeException(new SocketTimeoutException()))));
	}
}
