package com.flintclient.client.control.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fault isolation for one Control Center hook path (the HUD, the key bindings, the click counter...). The
 * first exception a path throws is reported once and turns that path off for the rest of the session, so a
 * bug in Flint costs a feature instead of the game, and a path failing every frame can't flood the log.
 * The other paths keep working. Checking {@link #on()} is a field read, so it's free on per-frame paths. Each failure is
 * also told to {@code feature.Faults}, which shows the player a short toast and keeps the record the QA crash sweep reads.
 * Render thread only.
 */
public final class HookGuard {
	/** Hears about a path's first failure. */
	@FunctionalInterface
	public interface Reporter {
		void failed(String path, Throwable error);
	}

	/** Every guard made, kept only under a QA tour (dev builds) so the crash sweep can arm them again ({@link #rearmAll}). */
	private static final List<HookGuard> QA_ALL = new ArrayList<>();

	private final String path;
	private final Reporter reporter;
	private boolean on = true;

	public HookGuard(String path, Reporter reporter) {
		this.path = Objects.requireNonNull(path, "path");
		this.reporter = Objects.requireNonNull(reporter, "reporter");
		if (com.flintclient.client.control.feature.Faults.QA) {
			synchronized (QA_ALL) {
				QA_ALL.add(this);
			}
		}
	}

	/** Whether the path still runs. */
	public boolean on() {
		return on;
	}

	public String path() {
		return path;
	}

	/**
	 * Turns the path off; only the first failure is reported. A linkage error counts too: on a Minecraft version Flint
	 * wasn't built for, a method or field the path uses may have changed, and that should cost the feature, not the game.
	 */
	public void fail(Throwable error) {
		if (!on) {
			return;
		}
		on = false;
		try {
			reporter.failed(path, error);
		} finally {
			// The safety net's record and the player's notice ("Flint turned off chat heads after an error").
			com.flintclient.client.control.feature.Faults.pathFailed(path, error);
		}
	}

	/** QA only ({@code Faults.rearm}): every guard that turned its path off runs it again. Does nothing outside a QA tour. */
	public static void rearmAll() {
		if (!com.flintclient.client.control.feature.Faults.QA) {
			return;
		}
		synchronized (QA_ALL) {
			for (HookGuard guard : QA_ALL) {
				guard.on = true;
			}
		}
	}
}
