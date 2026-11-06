package com.flintclient.client;

import java.time.Duration;
import java.time.Instant;

/**
 * Where Flint's own start-up time goes, for the log. Each step Flint runs on the render thread on the way to the title
 * screen is timed ({@link #begin}/{@link #end}); once the title screen is up ({@link #titleShown}) one line says how long
 * after the game process started that was and how much of it was Flint's own work, step by step. A few {@code nanoTime}
 * calls in all, and nothing at all after that line. Render thread only. Platform-free (1.8.9 shares it).
 */
public final class StartupTrace {
	private static final StringBuilder STEPS = new StringBuilder();
	private static long ownNanos;
	private static boolean reported;

	private StartupTrace() {
	}

	/** A step starts; pass the result to {@link #end}. */
	public static long begin() {
		return System.nanoTime();
	}

	/** A step started at {@code began} is over. Ignored once the line is written. */
	public static void end(String step, long began) {
		if (reported) {
			return;
		}
		long took = System.nanoTime() - began;
		ownNanos += took;
		if (STEPS.length() > 0) {
			STEPS.append(", ");
		}
		STEPS.append(step).append(' ').append(took / 1_000_000).append(" ms");
	}

	/** Whether the line has been written (the title screen came up). */
	public static boolean reported() {
		return reported;
	}

	/** The title screen is up with nothing loading over it: writes the line, once. */
	public static void titleShown() {
		if (reported) {
			return;
		}
		reported = true;
		String since;
		try {
			since = ProcessHandle.current().info().startInstant().map(start -> Duration.between(start, Instant.now()).toMillis() + " ms").orElse("?");
		} catch (RuntimeException e) {
			since = "?";
		}
		FlintLog.LOGGER.info("Flint start-up: title screen up {} after the game started; Flint's own steps on the way {} ms ({})", since,
			ownNanos / 1_000_000, STEPS);
	}
}
