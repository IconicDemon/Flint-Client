package com.flintclient.client.control;

import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.fx.MotionBlur;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.control.store.LayoutStore;
import com.flintclient.client.network.NetworkCore;
import com.flintclient.client.network.NetworkPlatform;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitPrefs;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * The Control Center's saved state: the global settings ({@code config/flintclient/control-center.json}) and
 * the layout profiles ({@code config/flintclient/layouts}), read once when the client starts and written only
 * when something changed, at the moments the screens choose (closing, Save, layout actions). Also answers the
 * questions about the game the screens and HUD ask, without allocating. Render thread only.
 */
public final class ControlState {
	private static ControlSettings settings;
	private static LayoutStore layouts;

	private ControlState() {
	}

	/** Reads both files (small, once) and lets the kit follow the saved interface preferences. */
	public static void init() {
		init(FabricLoader.getInstance().getConfigDir());
	}

	static void init(Path configDir) {
		settings = new ControlSettings(configDir);
		settings.load();
		layouts = new LayoutStore(configDir, settings);
		layouts.load();
		com.flintclient.client.control.feature.Features.init(configDir);
		Kit.bind(new SavedPrefs());
		applyGameplayBlur();
	}

	/** Pushes the saved gameplay motion blur choice (off by default) and strength to the effect; call after changing them. */
	public static void applyGameplayBlur() {
		ControlSettings saved = settings();
		MotionBlur.setStrength(saved.gameplayBlurStrength() / 100.0F);
		MotionBlur.setEnabled(saved.gameplayBlur());
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

	/** A world is loaded and the player is in it. */
	public static boolean inWorld() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level != null && minecraft.player != null;
	}

	/** Playing on a server rather than in singleplayer (an integrated server, even one opened to LAN). */
	public static boolean multiplayer() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getConnection() != null && !minecraft.isLocalServer();
	}

	/** Flint Network Core is running (it can be switched off in flint-performance.json). */
	public static boolean networkCoreRunning() {
		return NetworkCore.active();
	}

	/** The server has reported this player's latency (Flint Network Core reads what Minecraft already has). */
	public static boolean latencyKnown() {
		return NetworkCore.active() && NetworkCore.stats().latencyMillis() != NetworkPlatform.UNKNOWN;
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
