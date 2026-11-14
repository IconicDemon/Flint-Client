package com.flintclient.client.module.social;

import com.flintclient.client.FlintLog;
import com.flintclient.client.module.account.LauncherLink.LinkException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Instant Swap, the game's half (the launcher's {@code swap.rs} has the whole story): a mod the in-game Library adds or takes
 * out works as soon as the Library closes, by a second game the launcher started in the background with the new mods taking
 * this one's place, without a word to the player.
 *
 * <ul>
 * <li>In this game ({@link #libraryClosed}): once the new game is ready, this one holds its last frame on screen
 * ({@link #framesHeld}: nothing more is drawn), saves and leaves its world (or leaves its server) under it, saves its options
 * and hands over where it was and its window. It quits as soon as the new game is in the world and showing ({@code taken});
 * if the new game can't get there, this one lets go of the frame and goes back where it was, with a short notice.
 * <li>In the standby game ({@link #standby}): its window is made hidden, it is silent, steps back in the system and draws
 * nothing once it waits at its menu; meanwhile it reads the world the player is in from disk ahead. On the handover it reads
 * the options the old game saved, joins the same world or server still hidden, and once it is in and loaded shows itself in
 * the old window's place and mode, in front.
 * </ul>
 *
 * The engine work is the line's own {@link Engine}, found by name: a line without one has no Instant Swap (the Library isn't
 * offered there). Nothing runs while no swap is going on. Render thread, except the link calls. Platform-free.
 */
public final class InstantSwap {
	/** The launcher's mark on a standby game (swap.rs ENV_STANDBY): "1", or "urgent" when the old game has already ended. */
	public static final String ENV_STANDBY = "FLINT_SWAP_STANDBY";
	private static final String ENGINE = "com.flintclient.client.control.platform.Mc263SwapEngine";
	private static final long POLL_MILLIS = 1000;
	private static final long TAKEN_POLL_MILLIS = 100;
	/** The old game waits this long for the new one to be in the world before it goes back itself. */
	private static final long HANDOVER_LIMIT_MILLIS = 75_000;
	/** The new game gives up joining after this long (and quits: the old game then goes back). */
	private static final long JOIN_LIMIT_MILLIS = 50_000;
	/** In the world this many ticks (the nearby chunks built) before the new game shows itself. */
	private static final int SETTLE_TICKS = 2;
	/** After showing itself, the new game checks its window size and GUI scale this many ticks. */
	private static final int FIT_TICKS = 60;
	/** The most of a world's region files read ahead into the system's cache. */
	private static final long PRE_READ_BYTES = 256L << 20;
	private static final int STANDBY_TRIES = 3;

	/** What a line does for a swap. Render thread. */
	public interface Engine {
		/** Whether the game is at its main menu with everything loaded (a standby game is ready). */
		boolean atMainMenu();

		/** In a world or on a server, nothing over it, the nearby chunks built. */
		boolean inWorldReady();

		/** The world folder this game plays in, or null (not in singleplayer). */
		@Nullable String world();

		/** The server address this game is on, or null (not on a server, or on Realms). */
		@Nullable String server();

		/** The window now: {@code x, y, width, height, maximized, fullscreen}. */
		JsonObject window();

		/** The game's folder. */
		Path gameDir();

		/** Writes the options (keys, GUI scale, chat settings) to disk, for the new game. */
		void saveOptions();

		/**
		 * Reads the options the old game saved and applies them. True when that takes a moment (the player changed resource
		 * packs since this game started: they load first, hidden) and {@code then} runs once done; false when it is done now.
		 */
		boolean reloadOptions(Runnable then);

		/** Once a tick for a few seconds after showing: the window's real size and the GUI scale the options ask for. */
		default void settle() {
		}

		/** Whether the window has its final size and the player's GUI scale. */
		default boolean fitted() {
			return true;
		}

		/** The window's size and GUI scale, for the log. */
		default String fit() {
			return "";
		}

		/**
		 * Loads a mod jar into this running game (the line's hot loader), for a mod that needs no restart: true once it is in
		 * and working, false when it can't be (Instant Swap brings it in). Completes on the render thread; never fails.
		 */
		default java.util.concurrent.CompletableFuture<Boolean> loadLive(Path jar) {
			return java.util.concurrent.CompletableFuture.completedFuture(false);
		}

		/** Closes the screens over the world (Flint's menus, a pause menu), before the last frame is taken. */
		default void closeScreens() {
		}

		/** The game in front lets the new one take the front when it shows itself. */
		default void allowForeground() {
		}

		/** Saves and leaves the world, or leaves the server (Minecraft's own Save and Quit / Disconnect). */
		void leave();

		/** Quits the game (Minecraft's own stop). */
		void quit();

		/** Takes the old window's place, shape, mode and GUI scale, still hidden. */
		void show(@Nullable JsonObject window);

		/** Shows the window (under the launcher's still, which is always on top), not raised yet. */
		default void reveal() {
		}

		/**
		 * Keeps the game drawing at full speed while it comes in: Minecraft throttles a game nobody has touched for ten
		 * minutes to 10 FPS (long AFK), and a standby game has never had any input, so its first frames on screen took
		 * seconds. Called every tick from the handover until it has settled.
		 */
		default void keepActive() {
		}

		/** Saves the world now, on its server thread, without waiting (singleplayer; nothing on a server). */
		default void preSave() {
		}

		/** A world (or server) is loaded and the player is in it, drawn or not yet. */
		default boolean levelLoaded() {
			return inWorldReady();
		}

		/** Hides this game's window (the old game, once the new one is drawn). */
		default void hide() {
		}

		/** In a world or on a server, the player there (a screen may be open over it): a frame shows the world. */
		default boolean drawsWorld() {
			return inWorldReady();
		}

		/** Lets the compositor show the window (it was shown cloaked: drawn into, never on screen). */
		default void uncloak() {
		}

		/** Raises the window to the front (the still goes next). */
		default void front() {
		}

		/** Joins the world folder or the server (one is null), or stays at the main menu (both null). */
		void join(@Nullable String world, @Nullable String server);

		/** A short notice in Flint's style. */
		void notice(String text);

		/** The process steps back (below-normal CPU priority) or comes forward again (normal). */
		void background(boolean on);

		/** Whether the launcher raised this process to normal priority (or above). */
		default boolean promoted() {
			return false;
		}

		/** Writes the frame on screen now to {@code file} (PNG), then runs {@code done} (also when it couldn't). */
		void captureFrame(Path file, Runnable done);
	}

	private static final String MARK = System.getenv(ENV_STANDBY);
	private static final boolean STANDBY = MARK != null;
	/** The old game has already ended (not enough memory for two): the player is waiting, so this one doesn't step back. */
	private static final boolean URGENT = "urgent".equals(MARK);
	private static volatile boolean waiting = STANDBY;
	private static @Nullable Engine engine;
	private static boolean engineLooked;
	private static final Queue<Runnable> ON_RENDER = new ConcurrentLinkedQueue<>();
	private static volatile boolean pending;
	private static volatile boolean busy;
	private static volatile boolean readySent;
	private static long nextPoll;
	/** The last frame, written for the launcher's still window over the handover. */
	public static final String FRAME_FILE = ".flint-swap-frame.png";
	private static final long CAPTURE_LIMIT_MILLIS = 700;
	private static volatile boolean captured;
	private static boolean capturing;
	/** Ticks left before the frame is taken: the Library and Right Shift menu close first, so the frame is the world. */
	private static int captureIn;
	private static @Nullable Runnable captureNow;
	private static boolean captureSequential;
	private static long captureStarted;
	/** The old game holds its last frame (handing over). */
	private static volatile boolean frozen;
	private static volatile int standbyFailures;
	// The old game, handing over.
	private static boolean handingOver;
	private static long handedAt;
	private static @Nullable String leftWorld;
	private static @Nullable String leftServer;
	// The standby game, joining.
	private static volatile boolean joining;
	private static long joinStarted;
	private static int settled;
	private static int fitTicks;
	/**
	 * Coming in: 1 placed hidden at the final size (frames drawn there), 2 shown but cloaked (frames drawn into it, nothing on
	 * screen), 3 uncloaked under the still (frames drawn on screen), else 0. The still goes only after stage 3.
	 */
	private static int revealStage;
	private static volatile int framesSinceStage;
	/** Frames drawn at the final size before showing, and after showing before the still goes. */
	private static final int FRAMES_HIDDEN = 2;
	private static final int FRAMES_SHOWN = 3;
	private static @Nullable JsonObject handoverWindow;

	private InstantSwap() {
	}

	/**
	 * Loads the mods an install just put in ({@code files}, libraries first) into this game live (HotLoader), one after the
	 * other; {@code done} hears on the render thread whether every one went in (then no swap is needed for them), false at
	 * the first that can't (mixins, a main entrypoint, assets that need a reload...: Instant Swap brings it in).
	 */
	public static void loadLive(List<String> files, java.util.function.Consumer<Boolean> done) {
		Engine e = engine();
		if (e == null || files.isEmpty()) {
			done.accept(false);
			return;
		}
		Path mods = e.gameDir().resolve("mods");
		java.util.concurrent.CompletableFuture<Boolean> chain = java.util.concurrent.CompletableFuture.completedFuture(true);
		for (String file : files) {
			chain = chain.thenCompose(ok -> !ok ? java.util.concurrent.CompletableFuture.completedFuture(false)
				: e.loadLive(mods.resolve(file)));
		}
		chain.whenComplete((all, error) -> ON_RENDER.add(() -> done.accept(error == null && Boolean.TRUE.equals(all))));
	}

	/** Everything changed since the last swap went in live: the standby game the launcher started for it ends. */
	public static void dropStandby() {
		LibraryLink link = LibraryLink.get();
		if (link != null) {
			Thread t = new Thread(() -> {
				try {
					link.swapDrop();
				} catch (LinkException ex) {
					FlintLog.LOGGER.debug("Flint: Instant Swap: {}", ex.getMessage());
				}
			}, "Flint instant swap");
			t.setDaemon(true);
			t.start();
		}
	}

	/** This game is a standby game waiting to take over. */
	public static boolean standby() {
		return STANDBY;
	}

	/** A standby game that hasn't taken over yet: hidden. */
	public static boolean waiting() {
		return waiting;
	}

	/** Silent: a standby game until it shows itself. */
	public static boolean silent() {
		return waiting || joining;
	}

	/**
	 * Draw and present no frame: the old game holds its last one while it hands over, and a standby game waiting at its menu
	 * draws nothing at all.
	 */
	public static boolean framesHeld() {
		return frozen || waiting && readySent;
	}

	/** The frame cap of a standby game while it loads (Minecraft runs its loading work between frames), or -1. */
	public static int frameCap() {
		return waiting && !readySent && !URGENT && !promoted ? 30 : -1;
	}

	/** The launcher raised this standby game to normal priority (the player closed the Library): it loads at full speed. */
	private static volatile boolean promoted;
	private static long nextPromotedCheck;
	/** The old game said "getting ready" once for this wait. */
	private static boolean readyNoticeShown;

	/** Whether this line can swap (it has an engine) and Flint started this game. */
	public static boolean available() {
		return engine() != null && LibraryLink.get() != null;
	}

	private static @Nullable Engine engine() {
		if (!engineLooked) {
			engineLooked = true;
			try {
				engine = (Engine) Class.forName(ENGINE).getDeclaredConstructor().newInstance();
			} catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
				engine = null;
			}
		}
		return engine;
	}

	/**
	 * Early in a standby game (the client initialiser). The launcher started it at below-normal CPU priority (normal disk and
	 * memory priority) and raises it to normal once the player closes the Library; nothing to do here. (Windows' background
	 * mode, used before, starved its loading for minutes on a 16 GB PC.)
	 */
	public static void standbyStarting() {
		if (STANDBY) {
			FlintLog.LOGGER.info("Flint: Instant Swap: standby game starting ({})", URGENT ? "the player waits" : "below normal priority until the Library closes");
		}
	}

	/** The Library closed after a mod changed: this game hands over to the new one once it's ready. */
	public static void libraryClosed() {
		// A game that took over (started as a standby) swaps again like any other; one still waiting or joining doesn't.
		if (!waiting && !joining && !handingOver && available()) {
			pending = true;
			nextPoll = 0;
			readyNoticeShown = false;
			// The world is saved now, in the background on its own thread, while the player still plays (as an autosave
			// would): the save at the handover then has only what changed since, and the frozen picture is shorter.
			Engine e = engine();
			if (e != null) {
				e.preSave();
			}
		}
	}

	/** Once a game tick: nothing unless a swap is going on. */
	public static void tick() {
		for (Runnable r; (r = ON_RENDER.poll()) != null;) {
			r.run();
		}
		if (STANDBY) {
			standbyTick();
		}
		if (revealStage > 0) {
			revealTick();
		}
		if (fitTicks > 0) {
			Engine e = engine();
			fitTicks--;
			if (e != null) {
				e.keepActive();
				e.settle();
				if (fitTicks == 0) {
					FlintLog.LOGGER.info("Flint: Instant Swap: settled at {}", e.fit());
				}
			}
		}
		if (capturing && captureIn > 0) {
			if (--captureIn == 0 && captureNow != null) {
				captureNow.run();
				captureNow = null;
				captureStarted = System.currentTimeMillis();
			}
			return;
		}
		if (capturing) {
			Engine e = engine();
			if (e != null && (captured || System.currentTimeMillis() - captureStarted > CAPTURE_LIMIT_MILLIS)) {
				capturing = false;
				handOver(e, captureSequential);
			}
			return;
		}
		if ((pending || handingOver) && !busy) {
			long now = System.currentTimeMillis();
			if (now >= nextPoll) {
				nextPoll = now + (handingOver ? TAKEN_POLL_MILLIS : POLL_MILLIS);
				poll();
			}
		}
	}

	// ---------- the old game ----------

	private static void poll() {
		LibraryLink link = LibraryLink.get();
		if (link == null) {
			pending = false;
			return;
		}
		background(() -> {
			LibraryLink.SwapStatus status = link.swapStatus();
			ON_RENDER.add(() -> took(status));
		});
	}

	private static void took(LibraryLink.SwapStatus status) {
		Engine e = engine();
		if (e == null) {
			return;
		}
		if (handingOver) {
			if ("taken".equals(status.state())) {
				// The new game is in the world and drawn under the still: this window goes at once (the launcher fades the
				// still only once this game has ended, so the frozen old window never shows over the new game), then it ends.
				FlintLog.LOGGER.info("Flint: Instant Swap: the new game took over {} ms after the handover", System.currentTimeMillis() - handedAt);
				e.hide();
				e.quit();
			} else if ("failed".equals(status.state()) || System.currentTimeMillis() - handedAt > HANDOVER_LIMIT_MILLIS) {
				goBack(e, status);
			}
			return;
		}
		if (!pending) {
			return;
		}
		switch (status.state()) {
			case "ready" -> capture(e, status.sequential());
			case "failed" -> {
				pending = false;
				e.notice(what(status) + " couldn't be loaded · " + (status.reason() == null ? "Minecraft didn't start" : firstLine(status.reason())));
			}
			case "none" -> pending = false;
			default -> {
				// Preparing: the player keeps playing (no cover, nothing frozen) and hears once that the mod is on its way;
				// asked again in a second, and the swap happens as soon as the new game is ready.
				if (!readyNoticeShown) {
					readyNoticeShown = true;
					e.notice("Getting " + what(status) + " ready…");
				}
			}
		}
	}

	/** First the frame on screen goes to disk (the launcher shows it while the new game comes in), then the handover. */
	private static void capture(Engine e, boolean sequential) {
		pending = false;
		capturing = true;
		captured = false;
		captureSequential = sequential;
		captureStarted = System.currentTimeMillis();
		try {
			// Never an earlier swap's frame.
			Files.deleteIfExists(e.gameDir().resolve(FRAME_FILE));
			// The player sees the world come back with the mod, not a menu frozen: Flint's menus close first, and the frame
			// is taken two ticks later (the world drawn without them).
			e.closeScreens();
			Path frame = e.gameDir().resolve(FRAME_FILE);
			captureIn = 2;
			captureNow = () -> {
				try {
					e.captureFrame(frame, () -> captured = true);
				} catch (RuntimeException | LinkageError ex) {
					FlintLog.LOGGER.debug("Flint: Instant Swap: no still frame", ex);
					captureStarted = 0;
				}
			};
		} catch (IOException ex) {
			captured = false;
			captureStarted = 0;
		} catch (RuntimeException | LinkageError ex) {
			FlintLog.LOGGER.debug("Flint: Instant Swap: no still frame", ex);
			captured = false;
			captureStarted = 0;
		}
	}

	/** Holds the last frame, leaves the world or server under it (it is saved), hands over; quits once the new game is in. */
	private static void handOver(Engine e, boolean sequential) {
		LibraryLink link = LibraryLink.get();
		if (link == null) {
			pending = false;
			return;
		}
		pending = false;
		handingOver = true;
		handedAt = System.currentTimeMillis();
		frozen = true;
		leftWorld = e.world();
		leftServer = leftWorld == null ? e.server() : null;
		JsonObject window = e.window();
		FlintLog.LOGGER.info("Flint: Instant Swap: handing over ({})", leftWorld != null ? "singleplayer" : leftServer != null ? "server" : "main menu");
		long leaveStart = System.currentTimeMillis();
		try {
			e.allowForeground();
			e.saveOptions();
			e.leave();
		} catch (RuntimeException ex) {
			FlintLog.LOGGER.warn("Flint: Instant Swap: couldn't leave cleanly", ex);
		}
		FlintLog.LOGGER.info("Flint: Instant Swap: frame taken in {} ms, world saved and left in {} ms", leaveStart - captureStarted,
			System.currentTimeMillis() - leaveStart);
		String world = leftWorld;
		String server = leftServer;
		// The launcher's still window shows the frame written a moment ago, if it is there.
		window.addProperty("frame", captured && Files.isRegularFile(e.gameDir().resolve(FRAME_FILE)));
		busy = true;
		Thread go = new Thread(() -> {
			try {
				link.swapGo(world, server, window);
				FlintLog.LOGGER.info("Flint: Instant Swap: left and handed over {} ms after the Library closed", System.currentTimeMillis() - handedAt);
				if (sequential) {
					// Not enough memory for two games: the new one starts once this one has ended.
					ON_RENDER.add(e::quit);
				}
			} catch (LinkException ex) {
				ON_RENDER.add(() -> goBack(e, null));
			} finally {
				busy = false;
			}
		}, "Flint instant swap");
		go.setDaemon(true);
		go.start();
	}

	/** The new game couldn't take over: this one lets go of its frame and goes back where it was. */
	private static void goBack(Engine e, LibraryLink.@Nullable SwapStatus status) {
		FlintLog.LOGGER.warn("Flint: Instant Swap: the new game didn't take over; going back");
		handingOver = false;
		frozen = false;
		e.join(leftWorld, leftServer);
		String why = status != null && status.reason() != null ? firstLine(status.reason()) : "Minecraft didn't start";
		e.notice((status == null ? "The new mods" : what(status)) + " couldn't be loaded · " + why);
	}

	// ---------- the standby game ----------

	private static void standbyTick() {
		Engine e = engine();
		if (e == null) {
			return;
		}
		if (joining) {
			joiningTick(e);
			return;
		}
		if (waiting && !promoted && !URGENT) {
			long now = System.currentTimeMillis();
			if (now >= nextPromotedCheck) {
				nextPromotedCheck = now + 1000;
				if (e.promoted()) {
					promoted = true;
					FlintLog.LOGGER.info("Flint: Instant Swap: the player is waiting; loading at full speed");
				}
			}
		}
		if (!waiting || busy || readySent || !e.atMainMenu()) {
			return;
		}
		LibraryLink link = LibraryLink.get();
		if (link == null) {
			return;
		}
		readySent = true;
		Path dir = e.gameDir();
		background(() -> {
			link.swapReady();
			FlintLog.LOGGER.info("Flint: Instant Swap: ready to take over");
			preRead(dir);
			waitForHandover(link, e);
		});
	}

	private static void waitForHandover(LibraryLink link, Engine e) throws LinkException {
		while (true) {
			JsonObject go = link.swapWait(20_000);
			standbyFailures = 0;
			if (go == null) {
				continue;
			}
			if (go.has("quit")) {
				FlintLog.LOGGER.info("Flint: Instant Swap: not needed any more; quitting");
				ON_RENDER.add(e::quit);
				return;
			}
			ON_RENDER.add(() -> takeOver(e, go));
			return;
		}
	}

	/** The handover: the old game's options, then the same world or server, still hidden. */
	private static void takeOver(Engine e, JsonObject go) {
		if (!URGENT) {
			e.background(false);
		}
		joining = true;
		waiting = false;
		joinStarted = System.currentTimeMillis();
		try {
			if (e.reloadOptions(() -> join(e, go))) {
				FlintLog.LOGGER.info("Flint: Instant Swap: the player's resource packs changed; loading them first");
				return;
			}
		} catch (RuntimeException ex) {
			FlintLog.LOGGER.warn("Flint: Instant Swap: couldn't read the options", ex);
		}
		join(e, go);
	}

	private static void join(Engine e, JsonObject go) {
		JsonElement w = go.get("window");
		handoverWindow = w != null && w.isJsonObject() ? w.getAsJsonObject() : null;
		String world = SocialLink.string(go, "world");
		String server = SocialLink.string(go, "server");
		FlintLog.LOGGER.info("Flint: Instant Swap: joining ({})", world != null ? "singleplayer" : server != null ? "server" : "main menu");
		settled = world == null && server == null ? SETTLE_TICKS : 0;
		levelLogged = false;
		e.join(world, server);
	}

	/** Whether this game is coming in (shown, frames being counted before the still goes): a line may wait for each frame to be composed. */
	public static boolean comingIn() {
		return revealStage > 1;
	}

	/** A frame really went to the screen (render thread): counted while coming in, when it shows the world. */
	public static void framePresented() {
		if (revealStage > 1) {
			Engine e = engine();
			if (e != null && e.drawsWorld()) {
				framesSinceStage++;
			}
		}
	}

	/**
	 * Coming in, one gate for every swap (install or uninstall, the Library closed early or late, a world or a server): each
	 * step waits for frames that really reached the screen showing the world (framePresented), never for time.
	 * 1 hidden at the final size and GUI scale (fitted, two ticks) -> shown CLOAKED; 2 three frames presented at the final size
	 * -> uncloaked under the still; 3 three more on screen -> raised to the front; 4 three more in front -> the launcher is
	 * told (with the count) and only then fades the still. With no frames for 10 s it keeps waiting with the still up (never
	 * a window without a picture), and says so in the log.
	 */
	private static void revealTick() {
		Engine e = engine();
		if (e == null) {
			revealStage = 0;
			return;
		}
		e.keepActive();
		e.settle();
		long now = System.currentTimeMillis();
		int before = revealStage;
		if (revealStage == 1 && ++framesHiddenTicks >= FRAMES_HIDDEN && e.fitted()) {
			e.reveal();
			revealStage = 2;
		} else if (revealStage == 2 && framesSinceStage >= FRAMES_SHOWN) {
			e.uncloak();
			revealStage = 3;
		} else if (revealStage == 3 && framesSinceStage >= FRAMES_SHOWN) {
			e.front();
			revealStage = 4;
		} else if (revealStage == 4 && framesSinceStage >= FRAMES_SHOWN) {
			int frames = framesSinceStage;
			revealStage = 0;
			fitTicks = FIT_TICKS;
			FlintLog.LOGGER.info("Flint: Instant Swap: in front and drawn ({} frames presented), {} ms after the handover ({})", frames,
				now - joinStarted, e.fit());
			LibraryLink link = LibraryLink.get();
			if (link != null) {
				background(() -> link.swapJoined(frames));
			}
			return;
		}
		if (revealStage != before) {
			framesSinceStage = 0;
			stageSince = now;
			FlintLog.LOGGER.info("Flint: Instant Swap: coming in, step {} ({} ms after the handover)", revealStage, now - joinStarted);
		} else if (now - stageSince > 10_000 && !slowLogged) {
			slowLogged = true;
			FlintLog.LOGGER.warn("Flint: Instant Swap: no frames on screen for 10 s at step {}; the still stays up until they come", revealStage);
		}
	}

	private static int framesHiddenTicks;
	private static boolean levelLogged;
	private static long stageSince;
	private static boolean slowLogged;

	private static void joiningTick(Engine e) {
		e.keepActive();
		if (!levelLogged && e.levelLoaded()) {
			levelLogged = true;
			FlintLog.LOGGER.info("Flint: Instant Swap: world loaded {} ms after the handover; drawing the chunks around", System.currentTimeMillis() - joinStarted);
		}
		if (settled >= SETTLE_TICKS || e.inWorldReady()) {
			settled++;
		} else {
			settled = 0;
		}
		if (settled >= SETTLE_TICKS) {
			joining = false;
			// Placed and scaled while still hidden; shown only once it has drawn there, and the still goes only once it has
			// drawn on screen (revealTick): never a white, empty or wrongly scaled first frame.
			e.show(handoverWindow);
			revealStage = 1;
			framesSinceStage = 0;
			framesHiddenTicks = 0;
			stageSince = System.currentTimeMillis();
			slowLogged = false;
			FlintLog.LOGGER.info("Flint: Instant Swap: in the world, {} ms after the handover ({})", System.currentTimeMillis() - joinStarted, e.fit());
		} else if (System.currentTimeMillis() - joinStarted > JOIN_LIMIT_MILLIS) {
			// It never got in: it ends, and the old game goes back where it was.
			FlintLog.LOGGER.warn("Flint: Instant Swap: couldn't join in time; quitting");
			joining = false;
			e.quit();
		}
	}

	/**
	 * Reads the newest world's region files (the one the player is in, saved most recently) once, at the lowest priority, so
	 * opening it after the handover finds them in the system's cache. At most {@link #PRE_READ_BYTES}.
	 */
	private static void preRead(Path gameDir) {
		Path saves = gameDir.resolve("saves");
		if (!Files.isDirectory(saves)) {
			return;
		}
		try (Stream<Path> worlds = Files.list(saves)) {
			Path newest = worlds.filter(p -> Files.isRegularFile(p.resolve("level.dat")))
				.max(Comparator.comparingLong(p -> modified(p.resolve("level.dat")))).orElse(null);
			if (newest == null) {
				return;
			}
			List<Path> regions = new ArrayList<>();
			// 26.x keeps regions under dimensions/minecraft/<dimension>/region; older worlds under region and DIM-1/region.
			try (Stream<Path> files = Files.walk(newest, 6)) {
				files.filter(p -> p.toString().endsWith(".mca")).forEach(regions::add);
			}
			regions.sort(Comparator.comparingLong(InstantSwap::modified).reversed());
			long read = 0;
			byte[] buffer = new byte[1 << 16];
			for (Path region : regions) {
				if (read >= PRE_READ_BYTES || !waiting) {
					break;
				}
				try (InputStream in = Files.newInputStream(region)) {
					for (int n; (n = in.read(buffer)) > 0 && read < PRE_READ_BYTES;) {
						read += n;
					}
				}
			}
			FlintLog.LOGGER.info("Flint: Instant Swap: read {} MB of {} ahead", read >> 20, newest.getFileName());
		} catch (IOException | RuntimeException ex) {
			FlintLog.LOGGER.debug("Flint: Instant Swap: nothing read ahead", ex);
		}
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	// ---------- helpers ----------

	private interface Work {
		void run() throws LinkException;
	}

	private static void background(Work work) {
		busy = true;
		Thread thread = new Thread(() -> {
			try {
				work.run();
			} catch (LinkException ex) {
				FlintLog.LOGGER.debug("Flint: Instant Swap: {}", ex.getMessage());
				if (STANDBY && waiting) {
					// The launcher is gone or doesn't know this game: a standby game never shows itself on its own, so after a
					// few tries it quits (the player's game is untouched).
					readySent = false;
					if (++standbyFailures >= STANDBY_TRIES) {
						FlintLog.LOGGER.info("Flint: Instant Swap: the Flint app isn't answering; this standby game quits");
						Engine e = engine();
						if (e != null) {
							ON_RENDER.add(e::quit);
						}
					}
				}
			} catch (RuntimeException ex) {
				FlintLog.LOGGER.warn("Flint: Instant Swap failed", ex);
			} finally {
				busy = false;
			}
		}, "Flint instant swap");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	private static String what(LibraryLink.SwapStatus status) {
		return status.titles().isEmpty() ? "The new mods" : String.join(", ", status.titles());
	}

	private static String firstLine(String text) {
		int nl = text.indexOf('\n');
		String line = nl < 0 ? text : text.substring(0, nl);
		return line.length() > 140 ? line.substring(0, 139) + "…" : line;
	}
}
