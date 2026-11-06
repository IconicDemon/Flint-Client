package com.flintclient.client.network;

import com.flintclient.client.FlintClient;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.MinecraftClientHttpException;

/**
 * Joining an online server asks Mojang's session server first ({@code joinServer}). "The authentication servers are currently
 * not reachable" on a join that worked when tried again a few seconds later (owner, 2026-11-03 and 11-04): measured from the
 * owner's PC on 2026-11-04, Mojang's {@code session/minecraft/join} answers 503 to about one request in seven even at one
 * every 6 seconds, in bursts that last a second or two, and 503 then 429 to every request once more than about fifteen came in
 * a minute. Each comes back in a tenth of a second, and authlib reports a 5xx as "not reachable" (a 429 as a refusal with
 * Mojang's bare message). Tries a moment apart all land in the same burst, so a join that can't get through is tried again
 * after 1, 2, 3.5 and 5 seconds: about 12 seconds at most, well inside the 30 seconds a server waits for a login. Only
 * "unavailable" and 429 are retried; a refused login (expired session, no licence) is reported at once, as before.
 * {@code AuthlibTimeoutMixin} keeps each request on a fresh connection.
 */
public final class JoinRetry {
	/** The pause before each try after the first. */
	static final long[] WAITS_MS = {1_000, 2_000, 3_500, 5_000};
	static final int ATTEMPTS = WAITS_MS.length + 1;
	/** No new try starts later than this after the first one did: a server waits 30 seconds for the login in all. */
	static final long BUDGET_MS = 16_000;

	private JoinRetry() {
	}

	/** A join that can be run again. */
	@FunctionalInterface
	public interface Join {
		void run();
	}

	/** Waiting between tries (a test records instead of sleeping). */
	@FunctionalInterface
	interface Pause {
		void sleep(long ms) throws InterruptedException;
	}

	/** The time in milliseconds (a test moves it by hand). */
	@FunctionalInterface
	interface Clock {
		long nowMs();
	}

	public static void run(Join join) {
		run(join, Thread::sleep, System::currentTimeMillis);
	}

	static void run(Join join, Pause pause, Clock clock) {
		long started = clock.nowMs();
		for (int attempt = 1; ; attempt++) {
			try {
				join.run();
				if (attempt > 1) {
					FlintClient.LOGGER.info("Flint: joined through Mojang's session server on try {} of {}", attempt, ATTEMPTS);
				}
				return;
			} catch (Throwable failure) {
				if (!retryable(failure)) {
					throw JoinRetry.<RuntimeException>sneaky(failure);
				}
				long wait = attempt < ATTEMPTS ? WAITS_MS[attempt - 1] : -1;
				if (wait < 0 || clock.nowMs() - started + wait > BUDGET_MS) {
					FlintClient.LOGGER.info("Flint: Mojang's session server {} joining a server (try {} of {}), giving up", describe(failure), attempt, ATTEMPTS);
					throw JoinRetry.<RuntimeException>sneaky(finalFailure(failure));
				}
				FlintClient.LOGGER.info("Flint: Mojang's session server {} joining a server (try {} of {}), trying again in {} s", describe(failure), attempt, ATTEMPTS, wait / 1000.0);
				try {
					pause.sleep(wait);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw JoinRetry.<RuntimeException>sneaky(failure);
				}
			}
		}
	}

	/** Worth another try: Mojang couldn't be reached or answered 5xx, or it is limiting how fast joins come in (429). */
	static boolean retryable(Throwable failure) {
		return failure instanceof AuthenticationUnavailableException || failure instanceof AuthenticationException && status(failure) == 429;
	}

	/**
	 * What the player is shown once the tries run out: Minecraft's own "not reachable, please try again" for a 429 too (authlib
	 * would show Mojang's bare status text), anything else unchanged.
	 */
	static Throwable finalFailure(Throwable failure) {
		if (failure instanceof AuthenticationUnavailableException) {
			return failure;
		}
		return new AuthenticationUnavailableException(failure.getMessage(), failure);
	}

	/** The HTTP status Mojang answered, if authlib kept it (-1 otherwise). */
	static int status(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof MinecraftClientHttpException http) {
				return http.getStatus();
			}
		}
		return -1;
	}

	/** "answered 503", or "couldn't be reached (SocketTimeoutException)": for the log, never a token or a URL. */
	static String describe(Throwable failure) {
		int status = status(failure);
		if (status > 0) {
			return "answered " + status;
		}
		Throwable root = failure;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return "couldn't be reached (" + root.getClass().getSimpleName() + ")";
	}

	/** Rethrows authlib's checked exception unchanged (the method the join came from declares it). */
	@SuppressWarnings("unchecked")
	private static <T extends Throwable> T sneaky(Throwable failure) throws T {
		throw (T) failure;
	}
}
