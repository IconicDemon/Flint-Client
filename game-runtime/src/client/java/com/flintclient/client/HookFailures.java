package com.flintclient.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The switch-off record of {@link FlintMixinPlugin}'s hook guards, on a Minecraft version Flint wasn't built for: a Flint hook
 * that throws there is switched off for the rest of the run (logged once) and Minecraft carries on as if the hook weren't
 * there. Called from inside Minecraft's classes, so this class lives outside Flint's mixin package. Any thread.
 */
public final class HookFailures {
	private static final List<String> NAMES = new ArrayList<>();
	private static volatile boolean[] off = new boolean[0];

	private HookFailures() {
	}

	/** At transform time: the id a guarded hook checks. */
	static synchronized int register(String name) {
		NAMES.add(name);
		boolean[] grown = new boolean[NAMES.size()];
		System.arraycopy(off, 0, grown, 0, off.length);
		off = grown;
		return NAMES.size() - 1;
	}

	/** Whether hook {@code id} has been switched off. Called on every run of a guarded hook. */
	public static boolean off(int id) {
		boolean[] now = off;
		return id < now.length && now[id];
	}

	/** Hook {@code id} threw: switches it off, unless the throwable is one no hook should swallow (rethrown as is). */
	public static void failed(int id, Throwable error) {
		if (!(error instanceof RuntimeException) && !(error instanceof LinkageError)) {
			HookFailures.<RuntimeException>sneaky(error);
		}
		String name;
		synchronized (HookFailures.class) {
			if (off[id]) {
				return;
			}
			off[id] = true;
			name = NAMES.get(id);
		}
		FlintClient.LOGGER.error("Flint: the hook {} doesn't fit this Minecraft and is switched off", name, error);
	}

	@SuppressWarnings("unchecked")
	private static <T extends Throwable> void sneaky(Throwable error) throws T {
		throw (T) error;
	}
}
