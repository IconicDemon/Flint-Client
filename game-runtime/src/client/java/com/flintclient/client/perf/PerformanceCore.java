package com.flintclient.client.perf;

import com.flintclient.client.FlintClient;
import com.flintclient.client.network.platform.NetworkCoreModule;
import com.flintclient.client.network.platform.NetworkLogModule;
import com.flintclient.client.ui.FlintFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * Flint Performance Core in the game. Reads the launcher's settings once at start-up, starts the enabled
 * modules and tells them when the screen changes or a world is joined or left. Those calls come from the
 * end of {@code Gui.setScreen} (see GuiMixin), so they cost nothing while playing. A module that throws
 * is turned off for the session instead of taking the game down. Render thread only.
 */
public final class PerformanceCore {
	private static List<PerfModule> modules = List.of();
	private static Compat compat = Compat.of(id -> false);
	private static @Nullable FrameStatsLogModule frameStatsLog;
	private static @Nullable Screen screen;
	private static boolean inWorld;
	/** The launcher's Automatic Optimization setting as read at start-up; null until then. */
	private static @Nullable Boolean automaticOptimization;

	private PerformanceCore() {
	}

	public static void init() {
		PerfConfig config = PerfConfig.load(FabricLoader.getInstance().getConfigDir().resolve(PerfConfig.FILE_NAME));
		automaticOptimization = config.automaticOptimization();
		compat = Compat.detect();
		start(select(all(), config, compat));
		List<String> ids = new ArrayList<>();
		for (PerfModule module : modules) {
			ids.add(module.id());
		}
		FlintClient.LOGGER.info("Flint Performance Core: settings {}, Automatic Optimization {}, performance mods {}, modules {}",
			config.source(), config.automaticOptimization() ? "on" : "off", compat.describe(), ids.isEmpty() ? "none" : String.join(", ", ids));
	}

	/**
	 * Every module, in the order they start and are called back. graphicsCore comes after menuResources:
	 * its world-join check looks at what menuResources has just released. networkCore (Flint Network Core)
	 * lives in the network package; it and its developer log only observe.
	 */
	static List<PerfModule> all() {
		return List.of(new FrameStatsLogModule(), new MenuResourcesModule(), new AtlasBatchingModule(), new DecodeExecutorModule(),
			new FontPreloadModule(), new GraphicsCoreModule(), new GpuResourcesLogModule(), new FontTimingModule(), new NetworkCoreModule(),
			new NetworkLogModule());
	}

	/**
	 * Whether Automatic Optimization is on for this launch (the launcher's setting, read once at start-up), or
	 * null before start-up has read it. Read-only: the Control Center shows it as a fact.
	 */
	public static @Nullable Boolean automaticOptimization() {
		return automaticOptimization;
	}

	/** The performance mods detected at start-up. */
	public static Compat compat() {
		return compat;
	}

	/** Whether the module with this id is running: enabled, started, and not turned off since. */
	static boolean running(String id) {
		for (PerfModule module : modules) {
			if (module.id().equals(id)) {
				return true;
			}
		}
		return false;
	}

	/** The modules that run, in their listed order: an override in the config file wins over a module's default. */
	static List<PerfModule> select(List<PerfModule> all, PerfConfig config, Compat compat) {
		List<PerfModule> enabled = new ArrayList<>();
		for (PerfModule module : all) {
			if (config.enabled(module.id(), module.defaultEnabled(config, compat))) {
				enabled.add(module);
			}
		}
		return enabled;
	}

	/** Starts {@code enabled} and calls them back from now on; one that throws while starting stays off. */
	static void start(List<PerfModule> enabled) {
		List<PerfModule> started = new ArrayList<>();
		for (PerfModule module : enabled) {
			try {
				module.start();
				started.add(module);
			} catch (RuntimeException e) {
				FlintClient.LOGGER.warn("Flint Performance Core module {} couldn't start; it stays off", module.id(), e);
			}
		}
		modules = List.copyOf(started);
		frameStatsLog = null;
		for (PerfModule module : modules) {
			if (module instanceof FrameStatsLogModule log) {
				frameStatsLog = log;
			}
		}
	}

	/**
	 * From the end of {@code Gui.setScreen}: the screen now shown and whether a level is loaded. A world
	 * counts as joined from the first screen change with a level (for singleplayer, when the loading
	 * screen closes) until the first one without (leaving goes through a saving or progress screen after
	 * the level is cleared). A dimension change swaps one level for the next, so it stays inside one world;
	 * so does a server reconfiguring (a proxy switching servers) unless a screen opens while it has no level.
	 */
	public static void onScreenSet(@Nullable Screen current, boolean levelLoaded) {
		if (levelLoaded != inWorld) {
			inWorld = levelLoaded;
			each(levelLoaded ? PerfModule::onWorldJoined : PerfModule::onWorldLeft);
		}
		if (current != screen) {
			Screen previous = screen;
			screen = current;
			each(module -> module.onScreenChanged(previous, current));
		}
	}

	/**
	 * From the frame-time hook, every frame: one field check unless something records frame statistics.
	 * It also counts the frame for {@link FlintFrame} (a static increment).
	 */
	public static void onFrame(long frameNanos) {
		FlintFrame.advance();
		if (FrameStats.record(frameNanos) && frameStatsLog != null) {
			try {
				frameStatsLog.afterFrame();
			} catch (RuntimeException e) {
				turnOff(frameStatsLog, e);
			}
		}
	}

	private static void each(Consumer<PerfModule> callback) {
		for (PerfModule module : modules) {
			try {
				callback.accept(module);
			} catch (RuntimeException e) {
				turnOff(module, e);
			}
		}
	}

	private static void turnOff(PerfModule module, RuntimeException e) {
		FlintClient.LOGGER.warn("Flint Performance Core module {} failed; it's off for the rest of this session", module.id(), e);
		List<PerfModule> rest = new ArrayList<>(modules);
		rest.remove(module);
		modules = List.copyOf(rest);
		if (module == frameStatsLog) {
			frameStatsLog = null;
		}
	}
}
