package com.flintclient.legacy;

import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.rshift.Catalog;
import com.flintclient.legacy.mixin.KeyBindingAccessor;
import com.flintclient.legacy.mods.Combat;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import com.flintclient.legacy.shared.Engine189;
import com.flintclient.legacy.shared.Shared;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.KeyBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Flint on 1.8.9: its mods (the 1.8.9 backends of Flint's shared modules), Flint's shared settings ({@link Shared}) and its two
 * keys (the Flint menu, Right Shift; Zoom, C), both in Minecraft's own Controls under "Flint Client" and kept in step with the
 * shared settings. The mixins call in here; a mod that throws is switched off for the session (and logged once) instead of
 * taking the game down.
 */
public final class Flint {
	public static final Logger LOG = LogManager.getLogger("Flint");
	public static final String KEY_CATEGORY = "Flint Client";
	private static Modules modules;
	private static KeyBinding menuKey;
	private static KeyBinding zoomKey;
	private static final Set<String> FAULTED = new HashSet<String>();
	/** Drawing the shared HUD threw: it stays off for the session (as on every line), and the error is logged once. */
	private static boolean hudFailed;
	private static boolean[] toggleWasDown = new boolean[0];
	/** The codes last put in step between each key binding and its shared setting (menu, zoom). */
	private static final int[] synced = {Integer.MIN_VALUE, Integer.MIN_VALUE};

	private Flint() {
	}

	static void init(Path configDir) {
		Shared.init(configDir);
		modules = new Modules();
		// The shared Right Shift catalog lists only what 1.8.9 runs (its mods' backends, the settings they honour).
		Catalog.engine(new Engine189(modules));
		keys();
		syncKeys(true);
		toggleWasDown = new boolean[modules.array().length];
		for (Module m : modules.array()) {
			m.sync();
		}
		LOG.info("Flint Client for Minecraft 1.8.9: {} mods", modules.all().size());
	}

	public static boolean ready() {
		return modules != null;
	}

	public static Modules modules() {
		return modules;
	}

	public static KeyBinding menuKey() {
		return menuKey;
	}

	public static KeyBinding zoomKey() {
		return zoomKey;
	}

	/** Flint's keys, for Minecraft's options (so they are saved in options.txt and listed in Controls). */
	public static synchronized KeyBinding[] keys() {
		if (menuKey == null) {
			// Made on first use: Minecraft may read its options before Fabric starts Flint.
			menuKey = new KeyBinding("Flint Menu", Keyboard.KEY_RSHIFT, KEY_CATEGORY);
			zoomKey = new KeyBinding("Zoom", Keyboard.KEY_C, KEY_CATEGORY);
		}
		return new KeyBinding[] {menuKey, zoomKey};
	}

	/**
	 * Keeps the Zoom key binding and the shared Zoom key setting in step: a change in Minecraft's Controls goes to the shared
	 * setting, a change in Flint's menu (or a new profile) to the binding. At start-up ({@code first}) the shared setting wins, so a
	 * key chosen on another Minecraft line holds here too. (The menu's key is Minecraft's binding itself, as on every line.)
	 */
	private static void syncKeys(boolean first) {
		sync(1, zoomKey, modules.zoom.key, first);
	}

	private static void sync(int i, KeyBinding binding, Setting.Key setting, boolean first) {
		int bound = binding.getKeyCode();
		int shared = setting.code();
		if (bound == shared) {
			synced[i] = bound;
			return;
		}
		if (!first && bound != synced[i]) {
			setting.set(bound);
			synced[i] = bound;
			return;
		}
		binding.setKeyCode(shared);
		KeyBinding.resetMapping();
		synced[i] = shared;
		Minecraft mc = Minecraft.getInstance();
		if (mc.options != null && !first) {
			mc.options.save();
		}
	}

	public static void save() {
		Shared.save();
	}

	/** Whether {@code key} is held (by its own input, not by a mod holding it for you). */
	public static boolean physicallyDown(KeyBinding key) {
		return key != null && ((KeyBindingAccessor) key).flint$pressed();
	}

	/** A mod failed: it is switched off for the session. */
	public static void fault(Module m, Throwable e) {
		if (FAULTED.add(m.id)) {
			LOG.error("Flint switched off " + m.name + " after an error", e);
		}
		m.setEnabled(false);
	}

	/** The shared HUD threw while drawing: logged once; Flint's HUD stays off for the rest of the session. */
	public static void hudFailed(Throwable e) {
		if (!hudFailed) {
			hudFailed = true;
			LOG.error("Flint's HUD failed while drawing; it's off for the rest of this session", e);
		}
	}

	/** Whether the shared HUD is drawn (it is, unless it failed this session). */
	public static boolean hudOn() {
		return !hudFailed;
	}

	/** Every client tick (before Minecraft handles input). */
	public static void tick(Minecraft mc) {
		// Packs and worlds put into the game's folders in a form it can't read (a .rar, a pack a folder down), made readable.
		try {
			GameFolders.tick(mc);
		} catch (RuntimeException | LinkageError e) {
			LOG.warn("Flint: looking after the game's folders stopped", e);
		}
		// Resource packs the Flint app added while the game runs, shown at once (.flint-packs.json, looked at once a second).
		LivePacks189.tick(mc);
		if (modules == null) {
			return;
		}
		com.flintclient.client.control.hud.render.HudRenderer.tick();
		Presence.tick(mc);
		StoreCapes189.tick(mc);
		FriendNotices189.tick(mc);
		syncKeys(false);
		// The waypoints of the world or server you are in, whether Waypoints is on or not: the menu's list shows and adds them.
		com.flintclient.legacy.mods.Waypoints.refreshWorld();
		Module[] all = modules.array();
		for (Module m : all) {
			m.sync();
		}
		if (mc.world == null || mc.player == null) {
			return;
		}
		Combat.tick();
		boolean modKeys = Shared.settings().gameBool(GameOptions.MOD_KEYS);
		for (int i = 0; i < all.length; i++) {
			Module m = all[i];
			int code = m.toggleKey.code();
			boolean down = modKeys && code != 0 && mc.screen == null && (code > 0 ? Keyboard.isKeyDown(code) : Mouse.isButtonDown(code + 100));
			if (down && !toggleWasDown[i]) {
				m.setEnabled(!m.enabled());
				if (m.toggleNotify.on()) {
					mc.gui.setOverlayMessage(m.name + (m.enabled() ? " §aon" : " §7off"), false);
				}
			}
			toggleWasDown[i] = down;
			if (m.enabled()) {
				try {
					m.tick();
				} catch (RuntimeException e) {
					fault(m, e);
				}
			}
		}
	}

	/** After Minecraft handled this tick's input: the menu key opens the HUD page. */
	public static void afterInput(Minecraft mc) {
		if (menuKey == null) {
			return;
		}
		while (menuKey.consumeClick()) {
			if (mc.screen == null && mc.world != null) {
				// The HUD page, as on every line: the live game with every HUD mod editable, and the way into the window.
				mc.openScreen(new com.flintclient.client.control.rshift.HudPageScreen(null));
			}
		}
	}
}
