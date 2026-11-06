package com.flintclient.client.control;

import com.flintclient.client.StartupTrace;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.control.store.LayoutStore;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitPrefs;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/**
 * The Control Center's saved state: the global settings ({@code config/flintclient/control-center.json}) and
 * the layout profiles ({@code config/flintclient/layouts}), read once when the client starts and written only
 * when something changed, at the moments the screens choose (closing, Save, layout actions). The same on every
 * Minecraft line; the questions about the game itself are each engine's ({@code GameState} on 26.x). Render thread only.
 */
public final class ControlState {
	private static ControlSettings settings;
	private static LayoutStore layouts;
	/** Both files being read on a worker ({@link #readAhead}) for {@link #init} to take; null when nothing was started. */
	private static volatile @Nullable CompletableFuture<Read> ahead;
	private static volatile @Nullable Path aheadDir;

	private record Read(ControlSettings settings, LayoutStore layouts) {
	}

	private ControlState() {
	}

	/** Reads both files (small, once) and lets the kit follow the saved interface preferences. */
	public static void init() {
		init(FabricLoader.getInstance().getConfigDir());
	}

	/** Reads both files from {@code configDir} (a line whose loader names its own config folder calls this). */
	public static void init(Path configDir) {
		long began = StartupTrace.begin();
		Read read = takeAhead(configDir);
		if (read == null) {
			read = read(configDir);
		}
		settings = read.settings();
		layouts = read.layouts();
		StartupTrace.end("mod settings and HUD layouts", began);
		began = StartupTrace.begin();
		com.flintclient.client.control.feature.Features.init(configDir);
		Kit.bind(new SavedPrefs());
		applyGameplayBlur();
		StartupTrace.end("features", began);
	}

	/**
	 * Starts reading both files on a worker thread from the earliest point Flint runs (its pre-launch entry point, while
	 * Minecraft itself is still starting), so {@link #init} on the render thread only takes the result: the hundreds of
	 * model classes behind the mods' settings load there instead. Only Flint's own platform-free classes (and the JSON
	 * library) are loaded on that thread, never Minecraft's. If it fails, {@link #init} reads them itself as before.
	 */
	public static void readAhead(Path configDir) {
		aheadDir = configDir;
		ahead = CompletableFuture.supplyAsync(() -> read(configDir), task -> {
			Thread thread = new Thread(task, "Flint settings");
			thread.setDaemon(true);
			thread.start();
		});
	}

	private static Read read(Path configDir) {
		ControlSettings readSettings = new ControlSettings(configDir);
		readSettings.load();
		LayoutStore readLayouts = new LayoutStore(configDir, readSettings);
		readLayouts.load();
		return new Read(readSettings, readLayouts);
	}

	/** What {@link #readAhead} read for {@code configDir}, waiting for it if needed; null when there is nothing to take. */
	private static @Nullable Read takeAhead(Path configDir) {
		CompletableFuture<Read> pending = ahead;
		Path dir = aheadDir;
		ahead = null;
		aheadDir = null;
		if (pending == null || !configDir.equals(dir)) {
			return null;
		}
		try {
			return pending.join();
		} catch (CompletionException | java.util.concurrent.CancellationException e) {
			com.flintclient.client.FlintLog.LOGGER.warn("Flint: reading the settings ahead failed; reading them now", e.getCause() == null ? e : e.getCause());
			return null;
		}
	}

	/** Pushes the saved gameplay motion blur choice (off by default) and strength to the effect; call after changing them. */
	public static void applyGameplayBlur() {
		MenuHost.applyGameplayBlur(settings());
	}

	public static ControlSettings settings() {
		ensureLoaded();
		return settings;
	}

	public static LayoutStore layouts() {
		ensureLoaded();
		return layouts;
	}

	/** The layout the HUD shows. */
	public static Layout layout() {
		return layouts().active();
	}

	/** Writes whatever changed (settings and layouts); a no-op when nothing did. */
	public static void saveAll() {
		if (settings != null && settings.dirty()) {
			settings.save();
		}
		if (layouts != null && layouts.dirty()) {
			layouts.save();
		}
	}

	private static void ensureLoaded() {
		if (settings == null) {
			init();
		}
	}

	/** The kit reads the saved settings live, so a change applies on the next frame. */
	private static final class SavedPrefs implements KitPrefs {
		@Override
		public int uiScaleDelta() {
			return settings.interfaceSize().delta();
		}

		@Override
		public boolean highContrast() {
			return settings.highContrast();
		}

		@Override
		public boolean animationsEnabled() {
			return settings.animations();
		}

		@Override
		public boolean followMinecraftReducedMotion() {
			return settings.followMinecraftReducedMotion();
		}
	}
}
