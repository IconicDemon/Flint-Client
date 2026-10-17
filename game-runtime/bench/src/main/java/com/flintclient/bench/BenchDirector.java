package com.flintclient.bench;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PopupScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

/**
 * The scripted scenario: title screen, a fresh world, fixed camera moves, save-and-quit, reopen, and the
 * result file. A state machine advanced once per client tick from the end of {@code Minecraft.tick} (client
 * thread). Creating, leaving and reopening the world are blocking vanilla flows, so they are queued with
 * {@code Minecraft.schedule} and run where vanilla runs its own world-open tasks ({@code runAllTasks} at the
 * start of the next frame) rather than inside a tick.
 */
final class BenchDirector {
	private enum Phase {
		WAIT_MENU, MENU, CREATING, WAIT_JOIN, WAIT_TERRAIN, WARMUP, STATIC, SPIN, TRAVEL, RETURN, LEAVING, WAIT_TITLE,
		REOPENING, WAIT_REJOIN, WAIT_RETERRAIN, STATIC2, DONE
	}

	@FunctionalInterface
	private interface Task {
		void run() throws Exception;
	}

	static final String LEVEL_ID = "FlintBench";

	private static final long SECOND_NS = 1_000_000_000L;
	private static final long WATCHDOG_NS = 15 * 60 * SECOND_NS;
	private static final long WATCHDOG_GRACE_MS = 60_000;
	private static final long TITLE_TIMEOUT_NS = 300 * SECOND_NS;
	private static final long JOIN_TIMEOUT_NS = 180 * SECOND_NS;
	private static final long TERRAIN_TIMEOUT_NS = 60 * SECOND_NS;
	// In demo mode the server opens a "demo help" popup about a second after it adds the player
	// (DemoMode.tick), on every join. Measuring starts only once it has been closed, or after this wait.
	private static final long DEMO_POPUP_WAIT_NS = 10 * SECOND_NS;
	// After travel the player is put back at the start and held, so the save (and the reload) starts there.
	private static final long RETURN_HOLD_NS = SECOND_NS;

	// Segment lengths in seconds (quick mode divides them by 5).
	private static final double MENU_S = 10;
	private static final double WARMUP_S = 20;
	private static final double STATIC_S = 30;
	private static final double SPIN_S = 30;
	private static final double TRAVEL_S = 45;
	private static final double STATIC2_S = 20;

	// Camera script.
	private static final double HEIGHT_ABOVE_SURFACE = 30;
	private static final float START_YAW = 0;
	private static final float PITCH = 15; // positive pitch looks down
	private static final float SPIN_DEGREES_PER_S = 12;
	private static final float TRAVEL_YAW = -90; // facing +X
	private static final double TRAVEL_BLOCKS_PER_S = 16;

	private static final AtomicBoolean written = new AtomicBoolean();

	private static Result result;
	private static Phase phase = Phase.WAIT_MENU;
	private static long startNs;
	private static long phaseStartNs;
	private static long lastSampleNs;
	private static long createStartNs;
	private static long openStartNs;
	private static double startX;
	private static double startY;
	private static double startZ;
	// Failures inside scheduled tasks or vanilla callbacks, handled on the next tick.
	private static String pendingError;
	private static Throwable pendingCause;
	// Set when a popup (demo mode's help screen) was closed since the last world open.
	private static boolean popupClosed;
	// Every screen the scenario closed, so analysis can tell when one interrupted a segment.
	private static final List<Map<String, Object>> closedScreens = new CopyOnWriteArrayList<>();

	private BenchDirector() {
	}

	static void init(List<String> gcBeans) {
		long now = System.nanoTime();
		startNs = now;
		phaseStartNs = now;
		result = new Result(FlintBench.LABEL, FlintBench.SEED, FlintBench.QUICK);
		Env.captureJvm(result.env, gcBeans);
		result.scenario.put("levelId", LEVEL_ID);
		result.scenario.put("terrainStats", Probe.SODIUM ? "sodium" : "vanilla");
		result.scenario.put("closedScreens", closedScreens);
		result.anchor(now, uptimeMs());
		result.phase = phase.name();
		startWatchdog();
		// A crash (System.exit) or a kill by the runner still leaves a result saying how far the run got.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> writeAfterAbort("game exited before the scenario finished"), "flintbench-exit"));
	}

	/** Called at the end of every Minecraft.tick(). */
	static void clientTick(Minecraft mc) {
		if (result == null || phase == Phase.DONE) {
			return; // not initialised (cannot happen: entrypoints run before the first tick) or finished
		}
		long now = System.nanoTime();
		try {
			// Counts as input, so vanilla's AFK frame cap (30, then 10 fps) never kicks in.
			mc.getFramerateLimitTracker().onInputReceived();
			if (now - lastSampleNs >= SECOND_NS) {
				lastSampleNs = now;
				Probe.sample(mc, FlintBench.SAMPLES, now);
			}
			if (pendingError != null) {
				finish(mc, now, pendingError, pendingCause);
				return;
			}
			if (now - startNs > WATCHDOG_NS) {
				finish(mc, now, "timeout", null);
				return;
			}
			closeStrayScreen(mc, now);
			advance(mc, now);
		} catch (Throwable t) {
			finish(mc, now, t.toString(), t);
		}
	}

	private static void advance(Minecraft mc, long now) {
		switch (phase) {
			case WAIT_MENU -> {
				if (mc.gui.overlay() == null && mc.gui.screen() instanceof TitleScreen) {
					result.titleMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime();
					Env.captureGame(mc, result.env);
					beginSegment("menu", MENU_S, now);
					setPhase(Phase.MENU, now);
				} else if (now - phaseStartNs > TITLE_TIMEOUT_NS) {
					throw new IllegalStateException("title screen not reached; screen is " + describe(mc.gui.screen()));
				}
			}
			case MENU -> {
				if (segmentDue(now)) {
					endSegment(mc, now);
					setPhase(Phase.CREATING, now);
					popupClosed = false;
					schedule(mc, "creating the world", () -> createWorld(mc));
				}
			}
			case WAIT_JOIN -> {
				if (joined(mc)) {
					result.load.put("createToJoinMs", ms(now - createStartNs));
					setPhase(Phase.WAIT_TERRAIN, now);
				} else if (now - createStartNs > JOIN_TIMEOUT_NS) {
					throw new IllegalStateException("world did not open; screen is " + describe(mc.gui.screen()));
				}
			}
			case WAIT_TERRAIN -> {
				if (terrainWaitOver(mc, now, result.load, "createToTerrainMs", createStartNs) && demoPopupGone(mc, now)) {
					ensureSpectator(mc);
					chooseStart(mc);
					moveTo(mc.player, startX, START_YAW, true);
					beginSegment("warmup", WARMUP_S, now);
					setPhase(Phase.WARMUP, now);
				}
			}
			case WARMUP -> {
				moveTo(mc.player, startX, START_YAW, false);
				if (segmentDue(now)) {
					endSegment(mc, now);
					beginSegment("static", STATIC_S, now);
					setPhase(Phase.STATIC, now);
					result.scenario.put("spectator", mc.player.isSpectator());
				}
			}
			case STATIC -> {
				moveTo(mc.player, startX, START_YAW, false);
				if (segmentDue(now)) {
					endSegment(mc, now);
					beginSegment("spin", SPIN_S, now);
					setPhase(Phase.SPIN, now);
				}
			}
			case SPIN -> {
				// Rotation is set once per tick; vanilla interpolates it per frame (Entity.getYRot(partialTick)).
				moveTo(mc.player, startX, START_YAW + (float) (SPIN_DEGREES_PER_S * current().secondsIn(now)), false);
				if (segmentDue(now)) {
					endSegment(mc, now);
					beginSegment("travel", TRAVEL_S, now);
					setPhase(Phase.TRAVEL, now);
					moveTo(mc.player, startX, TRAVEL_YAW, true);
				}
			}
			case TRAVEL -> {
				// Position follows wall time, so speed is the same whatever the tick rate; vanilla interpolates
				// between the per-tick positions.
				moveTo(mc.player, startX + TRAVEL_BLOCKS_PER_S * current().secondsIn(now), TRAVEL_YAW, false);
				if (segmentDue(now)) {
					endSegment(mc, now);
					moveTo(mc.player, startX, START_YAW, true);
					setPhase(Phase.RETURN, now);
				}
			}
			case RETURN -> {
				moveTo(mc.player, startX, START_YAW, false);
				if (now - phaseStartNs >= RETURN_HOLD_NS) {
					setPhase(Phase.LEAVING, now);
					schedule(mc, "leaving the world", () -> leaveWorld(mc));
				}
			}
			case WAIT_TITLE -> {
				if (mc.level == null && mc.gui.overlay() == null && mc.gui.screen() instanceof TitleScreen) {
					setPhase(Phase.REOPENING, now);
					popupClosed = false;
					schedule(mc, "reopening the world", () -> reopenWorld(mc));
				} else if (now - phaseStartNs > JOIN_TIMEOUT_NS) {
					throw new IllegalStateException("title screen not reached after leaving; screen is " + describe(mc.gui.screen()));
				}
			}
			case WAIT_REJOIN -> {
				if (joined(mc)) {
					result.reload.put("openToJoinMs", ms(now - openStartNs));
					setPhase(Phase.WAIT_RETERRAIN, now);
				} else if (now - openStartNs > JOIN_TIMEOUT_NS) {
					throw new IllegalStateException("world did not reopen; screen is " + describe(mc.gui.screen()));
				}
			}
			case WAIT_RETERRAIN -> {
				// static2 is measured straight away, so the demo popup must be gone first.
				if (terrainWaitOver(mc, now, result.reload, "openToTerrainMs", openStartNs) && demoPopupGone(mc, now)) {
					moveTo(mc.player, startX, START_YAW, true);
					beginSegment("static2", STATIC2_S, now);
					setPhase(Phase.STATIC2, now);
				}
			}
			case STATIC2 -> {
				moveTo(mc.player, startX, START_YAW, false);
				if (segmentDue(now)) {
					endSegment(mc, now);
					finish(mc, now, null, null);
				}
			}
			default -> {
				// CREATING, LEAVING, REOPENING: a scheduled task runs the vanilla flow and blocks this thread
				// until it moves the phase on, so ticks only get here while it waits in the queue. Waiting
				// much longer means it was dropped (Minecraft.disconnect clears the queue).
				if (now - phaseStartNs > JOIN_TIMEOUT_NS) {
					throw new IllegalStateException(phase + ": the scheduled task never ran");
				}
			}
		}
	}

	// Scheduled tasks (client thread, outside any tick).

	private static void createWorld(Minecraft mc) throws Exception {
		LevelStorageSource saves = mc.getLevelSource();
		if (saves.levelExists(LEVEL_ID)) {
			// Same as deleting from the world list (WorldSelectionList).
			try (LevelStorageSource.LevelStorageAccess access = saves.createAccess(LEVEL_ID)) {
				access.deleteLevel();
			}
		}
		// Like vanilla's debug-world shortcut (SelectWorldScreen): spectator, cheats on, normal difficulty.
		LevelSettings settings = new LevelSettings(
			LEVEL_ID, GameType.SPECTATOR, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT
		);
		WorldOptions options = new WorldOptions(FlintBench.SEED, true, false);
		createStartNs = System.nanoTime();
		result.load.put("createStartNs", createStartNs);
		// Blocks (rendering loading frames) until the integrated server is ready, then the client logs in.
		mc.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, options, WorldPresets::createNormalWorldDimensions, mc.gui.screen());
		if (!mc.hasSingleplayerServer()) {
			throw new IllegalStateException("createFreshLevel did not start the integrated server");
		}
		setPhase(Phase.WAIT_JOIN, System.nanoTime());
	}

	private static void leaveWorld(Minecraft mc) {
		long start = System.nanoTime();
		// What the pause menu's "Save and Quit to Title" button runs (PauseScreen).
		mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
		long end = System.nanoTime();
		result.reload.put("leaveMs", ms(end - start));
		setPhase(Phase.WAIT_TITLE, end);
	}

	private static void reopenWorld(Minecraft mc) {
		openStartNs = System.nanoTime();
		result.reload.put("openStartNs", openStartNs);
		setPhase(Phase.WAIT_REJOIN, openStartNs);
		// Returns quickly: reading and upgrading the save continue in the background, then load on this thread.
		mc.createWorldOpenFlows().openWorld(LEVEL_ID, () -> fail("reopening " + LEVEL_ID + " was cancelled", null));
	}

	// Helpers.

	private static void schedule(Minecraft mc, String what, Task task) {
		mc.schedule(() -> {
			try {
				task.run();
			} catch (Throwable t) {
				fail(what + " failed: " + t, t);
			}
		});
	}

	private static void fail(String message, Throwable cause) {
		if (pendingError == null) {
			pendingError = message;
			pendingCause = cause;
		}
	}

	/**
	 * In the world and done loading. The screen alone is not enough: demo mode's help popup can replace the
	 * loading screen before the level is ready, so this also waits for the client's own "level loaded" signal
	 * (set when the loading tracker finishes, the same moment the loading screen would close).
	 */
	private static boolean joined(Minecraft mc) {
		ClientPacketListener connection = mc.getConnection();
		return mc.level != null && mc.player != null && connection != null && connection.hasClientLoaded()
			&& !(mc.gui.screen() instanceof LevelLoadingScreen);
	}

	/**
	 * True once terrain is ready (recording the time the first time) or will not become measurable (leaving
	 * it null). Called again on later ticks while the demo popup is awaited, so a recorded time is kept.
	 */
	private static boolean terrainWaitOver(Minecraft mc, long now, Map<String, Object> times, String key, long sinceNs) {
		if (times.get(key) != null) {
			return true;
		}
		if (Probe.terrainReady(mc)) {
			times.put(key, ms(now - sinceNs));
			return true;
		}
		return !Probe.terrainAvailable || now - phaseStartNs > TERRAIN_TIMEOUT_NS;
	}

	private static boolean demoPopupGone(Minecraft mc, long now) {
		return !mc.isDemo() || popupClosed || now - phaseStartNs > DEMO_POPUP_WAIT_NS;
	}

	// Demo help popup, pause menu and similar screens would pause the integrated server or cover the view.
	private static void closeStrayScreen(Minecraft mc, long now) {
		Screen screen = mc.gui.screen();
		if (mc.level != null && mc.player != null && screen != null && !(screen instanceof LevelLoadingScreen)) {
			FlintBench.LOGGER.info("[flintbench] closing {}", describe(screen));
			if (screen instanceof PopupScreen) {
				popupClosed = true;
			}
			Map<String, Object> closed = new LinkedHashMap<>();
			closed.put("screen", describe(screen));
			closed.put("phase", phase.name());
			closed.put("tNs", now);
			closedScreens.add(closed);
			mc.gui.setScreen(null);
		}
	}

	private static void ensureSpectator(Minecraft mc) {
		boolean spectator = mc.player.isSpectator();
		result.scenario.put("spectatorAtJoin", spectator);
		if (!spectator) {
			IntegratedServer server = mc.getSingleplayerServer();
			UUID id = mc.player.getUUID();
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player != null) {
					player.setGameMode(GameType.SPECTATOR);
				}
			});
			result.scenario.put("spectatorForced", true);
		}
	}

	/** World spawn column, 30 blocks above its surface. */
	private static void chooseStart(Minecraft mc) {
		ClientLevel level = mc.level;
		BlockPos spawn = level.getRespawnData().pos();
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.getX(), spawn.getZ());
		if (surface <= level.getMinY()) {
			surface = spawn.getY(); // spawn column not loaded on the client
		}
		startX = spawn.getX() + 0.5;
		startY = surface + HEIGHT_ABOVE_SURFACE;
		startZ = spawn.getZ() + 0.5;
		result.scenario.put("spawn", List.of(spawn.getX(), spawn.getY(), spawn.getZ()));
		result.scenario.put("surfaceY", surface);
		result.scenario.put("start", List.of(startX, startY, startZ));
	}

	/**
	 * Client-side placement (the singleplayer owner skips the server's movement checks; spectators have no
	 * collision). {@code snap} also resets the previous position and rotation so the camera jumps instead of
	 * interpolating across the move.
	 */
	private static void moveTo(LocalPlayer player, double x, float yaw, boolean snap) {
		if (snap) {
			player.snapTo(x, startY, startZ, yaw, PITCH);
		} else {
			player.setPos(x, startY, startZ);
			player.setYRot(yaw);
			player.setXRot(PITCH);
		}
		player.setDeltaMovement(0, 0, 0);
	}

	private static void beginSegment(String name, double seconds, long now) {
		FrameRecorder frames = FlintBench.FRAMES;
		result.segments.add(new Segment(name, now, Segment.durationNs(seconds, FlintBench.QUICK), frames.frames, frames.ticks, FlintBench.SAMPLES.count));
		result.anchor(now, uptimeMs());
		FlintBench.LOGGER.info("[flintbench] segment {}", name);
	}

	private static void endSegment(Minecraft mc, long now) {
		Segment segment = result.openSegment();
		if (segment != null) {
			segment.serverTickWindowNs = Probe.serverTickWindow(mc);
			segment.end(now, FlintBench.FRAMES.frames, FlintBench.FRAMES.ticks, FlintBench.SAMPLES.count, true);
		}
	}

	private static Segment current() {
		return result.openSegment();
	}

	private static boolean segmentDue(long now) {
		Segment segment = current();
		return segment == null || segment.isDue(now);
	}

	private static void setPhase(Phase next, long now) {
		phase = next;
		phaseStartNs = now;
		result.phase = next.name();
		FlintBench.LOGGER.info("[flintbench] phase {}", next);
	}

	/** Ends the run: closes the open segment, writes the result once, and quits the game. */
	private static void finish(Minecraft mc, long now, String error, Throwable cause) {
		boolean success = error == null;
		phase = Phase.DONE;
		try {
			Segment open = result.openSegment();
			if (open != null) {
				open.serverTickWindowNs = Probe.serverTickWindow(mc);
				open.end(now, FlintBench.FRAMES.frames, FlintBench.FRAMES.ticks, FlintBench.SAMPLES.count, success);
			}
			result.anchor(System.nanoTime(), uptimeMs());
		} catch (Throwable t) {
			FlintBench.LOGGER.warn("[flintbench] couldn't close the last segment", t);
		}
		if (success) {
			result.phase = Phase.DONE.name();
		} else {
			result.error = error;
			result.errorStack = cause == null ? null : stackTrace(cause);
			FlintBench.LOGGER.error("[flintbench] stopping: {}", error, cause);
		}
		if (written.compareAndSet(false, true)) {
			write();
		}
		mc.stop();
	}

	/** Writes the result file. Callers first claim the single write through {@link #written}. */
	private static void write() {
		Path out = Path.of(FlintBench.OUT).toAbsolutePath();
		try {
			ResultWriter.writeAtomically(out, result, FlintBench.FRAMES, FlintBench.SAMPLES, FlintBench.GC, Probe.THROTTLE_NAMES);
			FlintBench.LOGGER.info("[flintbench] done {}", out);
		} catch (Throwable t) {
			FlintBench.LOGGER.error("[flintbench] couldn't write {}", out, t);
		}
	}

	/**
	 * Writes what exists with {@code error}, from a thread other than the client thread, unless the result
	 * was already written; true if this call wrote it. The write is claimed before the error is set, so a
	 * result the client thread is writing at that moment is never marked as failed.
	 */
	private static boolean writeAfterAbort(String error) {
		if (!written.compareAndSet(false, true)) {
			return false;
		}
		FlintBench.LOGGER.error("[flintbench] {} (phase {})", error, result.phase);
		result.error = error;
		result.anchor(System.nanoTime(), uptimeMs());
		write();
		return true;
	}

	/**
	 * Backstop for a client thread that stops ticking (stuck load, endless dialog): a minute after the
	 * scenario deadline it writes whatever exists from this thread and halts the JVM. Normally the tick
	 * check above finishes the run first and this thread just sleeps.
	 */
	private static void startWatchdog() {
		Thread thread = new Thread(() -> {
			try {
				Thread.sleep(WATCHDOG_NS / 1_000_000 + WATCHDOG_GRACE_MS);
			} catch (InterruptedException e) {
				return;
			}
			if (writeAfterAbort("timeout (client thread unresponsive)")) {
				Runtime.getRuntime().halt(3);
			}
		}, "flintbench-watchdog");
		thread.setDaemon(true);
		thread.start();
	}

	private static Double ms(long nanos) {
		return Math.round(nanos / 1e5) / 10.0;
	}

	private static long uptimeMs() {
		return ManagementFactory.getRuntimeMXBean().getUptime();
	}

	private static String describe(Screen screen) {
		return screen == null ? "none" : screen.getClass().getName();
	}

	private static String stackTrace(Throwable t) {
		StringWriter text = new StringWriter();
		t.printStackTrace(new PrintWriter(text));
		return text.toString();
	}
}
