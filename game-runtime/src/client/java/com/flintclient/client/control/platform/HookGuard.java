package com.flintclient.client.control.platform;

import java.util.Objects;

/**
 * Fault isolation for one Control Center hook path (the HUD, the key bindings, the click counter...). The
 * first exception a path throws is reported once and turns that path off for the rest of the session, so a
 * bug in Flint costs a feature instead of the game, and a path failing every frame can't flood the log.
 * The other paths keep working. Checking {@link #on()} is a field read, so it's free on per-frame paths.
 * Render thread only.
 */
public final class HookGuard {
	/** Hears about a path's first failure. */
	@FunctionalInterface
	public interface Reporter {
		void failed(String path, RuntimeException error);
	}

	private final String path;
	private final Reporter reporter;
	private boolean on = true;

	public HookGuard(String path, Reporter reporter) {
		this.path = Objects.requireNonNull(path, "path");
		this.reporter = Objects.requireNonNull(reporter, "reporter");
	}

	/** Whether the path still runs. */
	public boolean on() {
		return on;
	}

	public String path() {
		return path;
	}

	/** Turns the path off; only the first failure is reported. */
	public void fail(RuntimeException error) {
		if (!on) {
			return;
		}
		on = false;
		reporter.failed(path, error);
	}
}
