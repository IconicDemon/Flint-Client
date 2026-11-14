package com.flintclient.client.control;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.module.account.LauncherLink;
import com.flintclient.client.render.Palette;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/**
 * One theme everywhere: the player's theme in the Flint app is the game's too (the Right Shift menu, the main menu, notices,
 * Friends, Cosmetics, every Flint screen), and a theme picked on the Right Shift menu's Theme page becomes the app's.
 *
 * <ul>
 * <li>At start-up ({@link #init}) the theme the app wrote for this launch ({@code config/flint-performance.json},
 * {@code ui.theme}) replaces the saved one, so the first frame is already in it.</li>
 * <li>While a Flint screen is open ({@link #tick}, each frame) the app is asked over the game link every
 * {@link #POLL_MILLIS} on a worker thread; a change made in the app is taken at the next frame. An app without the question
 * (an older Flint) is asked once.</li>
 * <li>A pick in the game ({@link #chosenInGame}) is sent to the app, which saves it and shows it at once.</li>
 * </ul>
 *
 * The look is kept in the menu's own setting ({@code ControlSettings.menuTheme}); {@link Palette}'s accent follows it.
 * Platform-free (every line, 1.8.9 included). Render thread, except the link calls on the worker.
 */
public final class ThemeSync {
	/** How often the app is asked while a Flint screen is open. */
	static final long POLL_MILLIS = 2000;
	private static final String CONFIG_FILE = "flint-performance.json";

	/** The look {@link Palette} was last given; null before the first. */
	private static MenuTheme.@Nullable Look applied;
	/** The app's theme as last seen (its own name), so only a change made there is taken. */
	private static @Nullable String launcherTheme;
	private static long lastPoll = Long.MIN_VALUE / 2;
	private static volatile boolean busy;
	/** False once the app turned the question down (an older Flint): it isn't asked again this session. */
	private static volatile boolean supported = true;
	/** Bumped at each pick in the game, so an answer asked for before it is dropped. */
	private static volatile int generation;
	/** What the worker heard for the render thread: the app's theme, and whether to take it (or only note it). */
	private static volatile @Nullable Heard heard;
	private static @Nullable ExecutorService worker;

	private record Heard(String theme, boolean take, int generation) {
	}

	private ThemeSync() {
	}

	/** Takes the theme the app wrote for this launch, then gives {@link Palette} the look (called by {@code ControlState.init}). */
	static void init(Path configDir) {
		try {
			String theme = launchTheme(configDir.resolve(CONFIG_FILE));
			if (theme != null) {
				launcherTheme = theme;
				take(theme);
			}
		} catch (RuntimeException e) {
			FlintLog.LOGGER.warn("Flint: couldn't read the app's theme for this launch", e);
		}
		apply();
	}

	/**
	 * Each frame a Flint screen draws: takes a theme the app changed to, keeps {@link Palette} on the chosen look and, every
	 * {@link #POLL_MILLIS}, asks the app again. Cheap: a few comparisons unless a question is due.
	 */
	public static void tick() {
		Heard got = heard;
		if (got != null) {
			heard = null;
			if (got.generation() == generation && !got.theme().equals(launcherTheme)) {
				launcherTheme = got.theme();
				if (got.take()) {
					take(got.theme());
				}
			}
		}
		apply();
		long now = System.currentTimeMillis();
		if (busy || !supported || now - lastPoll < POLL_MILLIS) {
			return;
		}
		LauncherLink link = LauncherLink.get();
		if (link == null) {
			supported = false;
			return;
		}
		lastPoll = now;
		busy = true;
		int asked = generation;
		run(() -> {
			try {
				String theme = link.theme();
				if (theme != null) {
					heard = new Heard(theme, true, asked);
				}
			} catch (LauncherLink.LinkException e) {
				if (!e.unreachable()) {
					supported = false;
				}
			} finally {
				busy = false;
			}
		});
	}

	/** The player picked {@code look} on the Theme page: the game shows it now and the app takes it too. */
	public static void chosenInGame(MenuTheme.Look look) {
		generation++;
		heard = null;
		String theme = look.launcherId();
		launcherTheme = theme;
		apply();
		LauncherLink link = LauncherLink.get();
		if (link == null) {
			return;
		}
		int picked = generation;
		run(() -> {
			try {
				link.setTheme(theme);
			} catch (LauncherLink.LinkException e) {
				// An app that can't take it (an older Flint): note what it shows, so the next answer doesn't undo the pick.
				try {
					String shown = link.theme();
					if (shown != null) {
						heard = new Heard(shown, false, picked);
					}
				} catch (LauncherLink.LinkException again) {
					supported = false;
				}
			}
		});
	}

	/** Makes the app's {@code theme} the menu's look (unknown names leave it as it is). */
	private static void take(String theme) {
		MenuTheme.Look look = MenuTheme.Look.forLauncher(theme);
		if (look == null) {
			return;
		}
		ControlSettings settings = ControlState.settings();
		if (settings.menuTheme().look() != look) {
			settings.setMenuTheme(settings.menuTheme().withLook(look));
		}
	}

	/** Gives {@link Palette} the accent of the chosen look, when it changed. */
	private static void apply() {
		MenuTheme.Look look = ControlState.settings().menuTheme().look();
		if (look == applied) {
			return;
		}
		applied = look;
		useLook(look);
	}

	/** {@link Palette}'s accent for {@code look}: Flint's own orange for Ember, the app's colours for the others. */
	static void useLook(MenuTheme.Look look) {
		Palette.useLight(look.light());
		if (look == MenuTheme.Look.FLINT) {
			Palette.useEmberAccent();
		} else {
			Palette.useAccent(look.accent, look.accentHover, look.accentPress, look.accentInk, look.accentText);
		}
	}

	/** The {@code ui.theme} the app wrote into this launch's config; null when there is none (or the file can't be read). */
	static @Nullable String launchTheme(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return themeIn(Files.readString(file, StandardCharsets.UTF_8));
		} catch (java.io.IOException | RuntimeException e) {
			return null;
		}
	}

	/** {@code ui.theme} in a config's text; null when absent or not text. */
	static @Nullable String themeIn(String json) {
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonObject()) {
			return null;
		}
		JsonElement ui = root.getAsJsonObject().get("ui");
		if (ui == null || !ui.isJsonObject()) {
			return null;
		}
		JsonElement theme = ((JsonObject) ui).get("theme");
		return theme != null && theme.isJsonPrimitive() && theme.getAsJsonPrimitive().isString() ? theme.getAsString() : null;
	}

	private static synchronized void run(Runnable task) {
		if (worker == null) {
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread thread = new Thread(r, "Flint theme");
				thread.setDaemon(true);
				return thread;
			});
		}
		worker.execute(task);
	}
}
