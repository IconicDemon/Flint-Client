package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.Hotbar;

/**
 * Minecraft's side of the Hotbar mod, read by {@code HudAutoHideMixin} as Minecraft draws its HUD: the hotbar, the experience
 * bar, the hearts, hunger (with air and a mount's health) and the armor row each at their own size (or the hotbar's), stacked
 * as Minecraft stacks them. With every part at the hotbar's size the whole group is Minecraft's own, scaled from the bottom
 * middle of the screen. The rest of the HUD keeps the GUI scale.
 *
 * <p>{@link #place} works out one part's transform into {@link #scale}, {@link #anchorX}, {@link #anchorY}, {@link #moveX} and
 * {@link #moveY}: the part is scaled around its anchor (the corner it keeps against the part under it), then moved. Render
 * thread only; fault-isolated; allocation-free.
 */
public final class Mc263Hotbar {
	public static final int HOTBAR = 0;
	public static final int EXPERIENCE = 1;
	public static final int HEALTH = 2;
	public static final int HUNGER = 3;
	public static final int ARMOR = 4;
	/** The held item's name over the bars: moved above them, at the GUI scale. */
	public static final int ITEM_NAME = 5;

	private static final int MODULE = Mods.index(Hotbar.ID);
	/** Minecraft's hotbar: 182×22 GUI units at the bottom middle. */
	private static final float HALF_WIDTH = 91;
	private static final float HOTBAR_HEIGHT = 22;
	/** The experience bar's band, from the hotbar's top to the hearts' bottom. */
	private static final float EXPERIENCE_HEIGHT = 8;
	/** The hearts' and hunger's first row: its bottom is 30 units over the screen's bottom. */
	private static final float STATUS_BOTTOM = 30;
	/** From the first heart row's bottom to the held item's name: hearts, armor and the gap Minecraft leaves. */
	private static final float STATUS_HEIGHT = 29;
	private static final HookGuard GUARD = new HookGuard("hotbar size",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));

	/** {@link #place}'s answer. */
	public static float scale = 1;
	public static float anchorX;
	public static float anchorY;
	public static float moveX;
	public static float moveY;

	private Mc263Hotbar() {
	}

	/**
	 * Works out where {@code part} is drawn on a {@code guiWidth} × {@code guiHeight} GUI; false (and nothing to do) while the
	 * mod is off. For {@link #ARMOR}, {@code heartRows} and {@code rowHeight} are Minecraft's heart rows and their spacing
	 * (the armor row sits on top of them); other parts ignore them.
	 */
	public static boolean place(int part, int guiWidth, int guiHeight, int heartRows, int rowHeight) {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return false;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			float hotbar = s.decimal(Hotbar.SCALE);
			float experience = size(s, Hotbar.EXPERIENCE_FOLLOWS, Hotbar.EXPERIENCE_SCALE, hotbar);
			// Minecraft centres the hotbar on half the width in whole units.
			float middle = guiWidth / 2;
			float bottom = guiHeight;
			// The bars stand on the hotbar's top, and the hearts and hunger on the experience bar's.
			float overHotbar = HOTBAR_HEIGHT * (hotbar - 1);
			float overExperience = overHotbar + EXPERIENCE_HEIGHT * (experience - 1);
			float edge = HALF_WIDTH * (hotbar - 1);
			switch (part) {
				case HOTBAR -> set(hotbar, middle, bottom, 0, 0);
				case EXPERIENCE -> set(experience, middle, bottom - HOTBAR_HEIGHT, 0, -overHotbar);
				case HEALTH -> set(size(s, Hotbar.HEALTH_FOLLOWS, Hotbar.HEALTH_SCALE, hotbar), middle - HALF_WIDTH, bottom - STATUS_BOTTOM, -edge,
					-overExperience);
				case HUNGER -> set(size(s, Hotbar.HUNGER_FOLLOWS, Hotbar.HUNGER_SCALE, hotbar), middle + HALF_WIDTH, bottom - STATUS_BOTTOM, edge,
					-overExperience);
				case ARMOR -> {
					// The armor row's bottom is this far over the first heart row's: it moves up as the hearts grow.
					float hearts = (Math.max(heartRows, 1) - 1) * rowHeight + 10;
					float health = size(s, Hotbar.HEALTH_FOLLOWS, Hotbar.HEALTH_SCALE, hotbar);
					set(size(s, Hotbar.ARMOR_FOLLOWS, Hotbar.ARMOR_SCALE, hotbar), middle - HALF_WIDTH, bottom - STATUS_BOTTOM - hearts, -edge,
						-overExperience - hearts * (health - 1));
				}
				default -> {
					float tallest = Math.max(size(s, Hotbar.HEALTH_FOLLOWS, Hotbar.HEALTH_SCALE, hotbar),
						Math.max(size(s, Hotbar.HUNGER_FOLLOWS, Hotbar.HUNGER_SCALE, hotbar), size(s, Hotbar.ARMOR_FOLLOWS, Hotbar.ARMOR_SCALE, hotbar)));
					set(1, 0, 0, 0, -overExperience - STATUS_HEIGHT * (tallest - 1));
				}
			}
			return scale != 1 || moveX != 0 || moveY != 0;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	private static float size(ModuleState s, BoolSetting follows, FloatSetting own, float hotbar) {
		return s.bool(follows) ? hotbar : s.decimal(own);
	}

	private static void set(float size, float x, float y, float dx, float dy) {
		scale = size;
		anchorX = x;
		anchorY = y;
		moveX = dx;
		moveY = dy;
	}
}
