package com.flintclient.client.control.feature;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Flint's safety net, the one place every caught failure of Flint's own code is told about, so a bug in Flint costs a mod
 * or a screen for the session, never the game:
 * <ul>
 * <li>a module that threw from its hook (its HUD drawing, a tick, a render step) is switched off for the session
 * ({@link #moduleFailed}): {@link ServerRules#blocked} then answers true for it, so {@link Mods#on}, the HUD and the mod's
 * card all treat it as off (the card says "Off after an error"), while the player's saved choice stays as it was;</li>
 * <li>a hook path that turned itself off ({@code platform.HookGuard}) and a Flint screen that closed after an error
 * ({@code platform.HostScreen}) are recorded too;</li>
 * <li>each leaves one short notice ("Zoom was turned off · error") that the engine shows as a toast
 * ({@link #takeNotice}, read once a tick).</li>
 * </ul>
 * Each failure is logged once, with its stack trace. Checking {@link #off} is an array read (per-frame paths). Platform-free:
 * the same on every line, 1.8.9 included. Render thread, except {@link #records} (any thread).
 */
public final class Faults {
	/** Under the QA tour (dev only): guards can be armed again after a failure, so a sweep finds every failure, not the first. */
	public static final boolean QA = System.getProperty("flintqa.script") != null;
	private static final boolean[] OFF = new boolean[ModuleRegistry.size()];
	private static final ArrayDeque<String> NOTICES = new ArrayDeque<>();
	private static final List<Record> RECORDS = new ArrayList<>();
	private static final int MAX_RECORDS = 500;
	private static final int MAX_NOTICES = 8;
	private static int revision;
	private static int recorded;
	/**
	 * QA only: the module whose next HUD draw throws (-1: none), and whether the next Flint screen draw throws, so the sweep
	 * can check the safety net end to end in the real game. Set only by the QA tour ({@link #qaInject}); a field read where
	 * it is checked.
	 */
	public static int qaFailModule = -1;
	public static boolean qaFailScreen;

	/** One caught failure: where (a module's name, a hook path, a screen), doing what, and the error. */
	public record Record(String kind, String where, String step, Throwable error) {
		@Override
		public String toString() {
			return kind + " " + where + " (" + step + "): " + error;
		}
	}

	private Faults() {
	}

	/** Whether module {@code index} was switched off for the session after an error. */
	public static boolean off(int index) {
		return index >= 0 && index < OFF.length && OFF[index];
	}

	/** Changes each time a module is switched off (or, under QA, back on), to redo what was worked out from it. */
	public static int revision() {
		return revision;
	}

	/**
	 * Module {@code index} threw while {@code step} ("drawing", "ticking"...): logged once with its stack, switched off for
	 * the session, and a notice queued. Returns whether this was its first failure.
	 */
	public static boolean moduleFailed(int index, String step, Throwable error) {
		if (index < 0 || index >= OFF.length) {
			return false;
		}
		if (OFF[index]) {
			return false;
		}
		OFF[index] = true;
		revision++;
		ModuleDescriptor module = ModuleRegistry.all().get(index);
		FlintLog.LOGGER.error("Flint turned off {} after an error while {}; it stays off for this session", module.name(), step, error);
		record(new Record("module", module.name(), step, error));
		notice(module.name() + " was turned off" + TAIL);
		return true;
	}

	/** {@link #moduleFailed(int, String, Throwable)} by the module's id (an unknown id is only logged). */
	public static boolean moduleFailed(String id, String step, Throwable error) {
		int index = ModuleRegistry.indexOf(id);
		if (index < 0) {
			FlintLog.LOGGER.error("Flint: {} failed while {}", id, step, error);
			record(new Record("module", id, step, error));
			return false;
		}
		return moduleFailed(index, step, error);
	}

	/**
	 * A hook path turned itself off after its first failure ({@code HookGuard}, which has already logged it): recorded, and a
	 * notice queued. Never throws.
	 */
	public static void pathFailed(String path, Throwable error) {
		record(new Record("path", path, "running", error));
		notice(capital(path) + " turned off" + TAIL);
	}

	/** A Flint screen closed instead of crashing the game ({@code HostScreen}, which has already logged it). */
	public static void screenFailed(String screen, String step, Throwable error) {
		record(new Record("screen", screen, step, error));
		notice(capital(screen) + " closed" + TAIL);
	}

	/**
	 * The notice to show now, or null: several queued at once become one ("Flint turned off Zoom and 2 more after errors"),
	 * so a version Flint wasn't built for doesn't bury the screen in toasts.
	 */
	public static synchronized @Nullable String takeNotice() {
		String first = NOTICES.poll();
		if (first == null) {
			return null;
		}
		int more = NOTICES.size();
		NOTICES.clear();
		if (more == 0) {
			return first;
		}
		int cut = first.lastIndexOf(TAIL);
		return (cut > 0 ? first.substring(0, cut) : first) + " and " + more + " more · errors";
	}

	/** Every failure caught so far this session (oldest first; at most the first 500), for the QA sweep and the log. */
	public static synchronized List<Record> records() {
		return List.copyOf(RECORDS);
	}

	/** How many failures were caught so far (also past the 500 kept). */
	public static synchronized int count() {
		return recorded;
	}

	/**
	 * QA only (dev builds): every module switched off after an error is on again, and every hook path that turned itself off
	 * runs again, so the sweep goes on finding failures past the first. Does nothing outside a QA tour.
	 */
	public static void rearm() {
		if (!QA) {
			return;
		}
		java.util.Arrays.fill(OFF, false);
		revision++;
		com.flintclient.client.control.platform.HookGuard.rearmAll();
		synchronized (Faults.class) {
			NOTICES.clear();
		}
	}

	/**
	 * QA only: makes module {@code moduleId}'s next HUD draw throw ("" for none), and with {@code screen} the next draw of the
	 * Flint screen shown. Does nothing outside a QA tour.
	 */
	public static void qaInject(String moduleId, boolean screen) {
		if (!QA) {
			return;
		}
		qaFailModule = moduleId.isEmpty() ? -1 : ModuleRegistry.indexOf(moduleId);
		qaFailScreen = screen;
	}

	/** The failure QA asked for, thrown where it is checked. */
	public static RuntimeException qaFailure(String where) {
		qaFailModule = -1;
		qaFailScreen = false;
		return new IllegalStateException("QA: a failure injected into " + where + " (the safety net's own check)");
	}

	/** For tests: as at start-up. */
	static synchronized void reset() {
		java.util.Arrays.fill(OFF, false);
		NOTICES.clear();
		RECORDS.clear();
		recorded = 0;
		revision++;
	}

	private static synchronized void record(Record r) {
		recorded++;
		if (RECORDS.size() < MAX_RECORDS) {
			RECORDS.add(r);
		}
	}

	/** The notices' grey second part (the toast draws what follows " · " in grey). */
	private static final String TAIL = " · error";

	private static String capital(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static synchronized void notice(String text) {
		if (NOTICES.size() < MAX_NOTICES) {
			NOTICES.add(text);
		}
	}
}
