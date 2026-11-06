package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.LookMods.DarkMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Minecraft 26.3's side of Dark Mode: Minecraft's own menu pictures (buttons, fields, sliders, inventories, menu
 * backgrounds) drawn in the chosen shade instead of their light grey, buttons under the pointer in the accent, the
 * inventory titles in a light grey to read on it, and the hotbar too if wanted. Flint's own screens and the rest of the HUD
 * are left alone. Its key turns it on and off. Fault-isolated; nothing is allocated per picture.
 */
public final class Mc263DarkMode {
	private static final HookGuard GUARD = new HookGuard("dark mode",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int DARK = Mods.index(DarkMode.ID);
	/** Minecraft's menu grey (#C6C6C6): the shade replaces it, so pictures are scaled by shade / this. */
	private static final float GREY = 198.0F;
	private static final String[] SPRITES = {"widget/", "container/", "recipe_book/", "advancements/", "statistics/", "social_interactions/",
		"popup/", "gamemode_switcher/", "spectator/", "transferable_list/"};
	private static boolean held;

	private Mc263DarkMode() {
	}

	/** Each client tick: the key turns the dark menus on and off. */
	public static void tick() {
		if (!GUARD.on() || !Mods.on(DARK)) {
			held = false;
			return;
		}
		try {
			ModuleState s = Mods.state(DARK);
			String key = s.text(DarkMode.KEY);
			boolean down = !key.isEmpty() && Mc263Features.keyHeld(key);
			if (down && !held) {
				boolean on = !s.bool(DarkMode.ENABLED);
				if (s.set(DarkMode.ENABLED, on)) {
					ControlState.settings().recordChange(DarkMode.ID);
					ControlState.saveAll();
					com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal("Dark menus " + (on ? "on" : "off")), false);
				}
			}
			held = down;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** 0 off; 1 in a menu of Minecraft's; 2 playing with no menu open. */
	private static int where() {
		if (!Mods.on(DARK) || !Mods.state(DARK).bool(DarkMode.ENABLED)) {
			return 0;
		}
		Screen screen = Minecraft.getInstance().gui.screen();
		if (screen == null) {
			return 2;
		}
		return screen.getClass().getName().startsWith("com.flintclient.") ? 0 : 1;
	}

	/** The colour a GUI sprite is drawn with ({@code color}: Minecraft's tint, usually white). */
	public static int sprite(Identifier sprite, int color) {
		if (!GUARD.on()) {
			return color;
		}
		try {
			int where = where();
			if (where == 0 || !sprite.getNamespace().equals("minecraft")) {
				return color;
			}
			String path = sprite.getPath();
			ModuleState s = Mods.state(DARK);
			if (path.startsWith("hud/hotbar")) {
				return s.bool(DarkMode.HOTBAR) ? shade(color, s.rgb(DarkMode.SHADE)) : color;
			}
			if (where != 1 || !menuSprite(path)) {
				return color;
			}
			boolean lit = path.contains("highlighted") || path.contains("_hover") || path.endsWith("_selected");
			return shade(color, lit ? s.rgb(DarkMode.ACCENT) : s.rgb(DarkMode.SHADE));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return color;
		}
	}

	private static boolean menuSprite(String path) {
		for (String prefix : SPRITES) {
			if (path.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** The colour a whole GUI texture (an inventory's background, a menu background) is drawn with. */
	public static int texture(Identifier texture, int color) {
		if (!GUARD.on()) {
			return color;
		}
		try {
			if (where() != 1 || !texture.getNamespace().equals("minecraft")) {
				return color;
			}
			String path = texture.getPath();
			if (!path.startsWith("textures/gui/") || path.startsWith("textures/gui/title/") || path.contains("book")) {
				return color;
			}
			return shade(color, Mods.state(DARK).rgb(DarkMode.SHADE));
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return color;
		}
	}

	/** Minecraft's dark grey menu text (inventory titles, labels) in light grey on the dark background; other text as it is. */
	public static int label(int color) {
		if ((color & 0xFFFFFF) != 0x404040 || !GUARD.on()) {
			return color;
		}
		try {
			return where() == 1 ? color & 0xFF000000 | 0xE0E0E0 : color;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return color;
		}
	}

	/** {@code color} scaled so Minecraft's grey comes out as {@code shade}. */
	private static int shade(int color, int shade) {
		float r = Math.min(1, (shade >> 16 & 255) / GREY);
		float g = Math.min(1, (shade >> 8 & 255) / GREY);
		float b = Math.min(1, (shade & 255) / GREY);
		return color & 0xFF000000 | Math.round((color >> 16 & 255) * r) << 16 | Math.round((color >> 8 & 255) * g) << 8 | Math.round((color & 255) * b);
	}
}
