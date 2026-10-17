package com.flintclient.client.control.hud;

/**
 * Where Minecraft 26.3 draws its own HUD elements, in GUI units, for the HUD editor's outlines, snapping and
 * overlap warnings, and for checking presets. The numbers come from the game's own drawing code ({@code Hud},
 * {@code ChatComponent}, {@code BossHealthOverlay}); the caller passes in whatever depends on options or state
 * (chat size, how many boss bars, the scoreboard's size). Each call makes a {@link HudRect}, so work these out
 * when the screen size or that state changes, not every frame.
 */
public final class VanillaAreas {
	/** The hotbar's size, and the width every bar above it shares. */
	public static final int HOTBAR_WIDTH = 182;
	public static final int HOTBAR_HEIGHT = 22;
	/** Hearts, food, armour and air with one row of hearts (more health rows push the armour row higher). */
	public static final int STATUS_HEIGHT = 27;
	/** The chat's size with Minecraft's default chat options (width 100 %, unfocused height, scale 100 %). */
	public static final int DEFAULT_CHAT_WIDTH = 320;
	public static final int DEFAULT_CHAT_HEIGHT = 90;
	/** Chat lines end this far above the bottom of the screen. */
	public static final int CHAT_BOTTOM_MARGIN = 40;

	private VanillaAreas() {
	}

	/** The hotbar: 182×22 at the bottom centre. */
	public static HudRect hotbar(float screenWidth, float screenHeight) {
		return new HudRect(centreLeft(screenWidth), screenHeight - HOTBAR_HEIGHT, HOTBAR_WIDTH, HOTBAR_HEIGHT);
	}

	/**
	 * The status bars straight above the hotbar: hearts and food 39 units up, armour and air a row above them, the
	 * experience bar and level in between.
	 */
	public static HudRect statusBars(float screenWidth, float screenHeight) {
		return new HudRect(centreLeft(screenWidth), screenHeight - HOTBAR_HEIGHT - STATUS_HEIGHT, HOTBAR_WIDTH, STATUS_HEIGHT);
	}

	/**
	 * The boss bars at the top centre: the first name at y 3 and its bar at 12, then one every 19 units. Names
	 * wider than the bar stick out on both sides; this covers the bar's width.
	 */
	public static HudRect bossBars(float screenWidth, int bars) {
		int shown = Math.max(bars, 1);
		return new HudRect(centreLeft(screenWidth), 3, HOTBAR_WIDTH, 14 + 19 * (shown - 1));
	}

	/**
	 * The chat in the bottom-left corner when it is full: {@code chatWidth} and {@code chatHeight} are the sizes
	 * Minecraft works out from the chat options (its {@code ChatComponent.getWidth/getHeight}; see
	 * {@link #DEFAULT_CHAT_WIDTH}), and {@code scale} the chat scale option. Lines are drawn upwards from 40 units
	 * above the bottom, with a 4-unit margin either side of the text; a smaller scale makes the lines shorter but
	 * the text still wraps at the chat width.
	 */
	public static HudRect chat(float screenHeight, int chatWidth, int chatHeight, float scale) {
		float s = scale > 0 ? scale : 1.0F;
		float bottom = (float) Math.floor((screenHeight - CHAT_BOTTOM_MARGIN) / s) * s;
		float width = ((float) Math.ceil(chatWidth / s) + 12.0F) * s;
		float height = chatHeight * s;
		return new HudRect(0, bottom - height, width, height);
	}

	/**
	 * The scoreboard on the right, for an objective whose widest line is {@code contentWidth} units with
	 * {@code lines} scores (Minecraft shows at most 15): its bottom sits a third of its height below the middle of
	 * the screen, with a title row above.
	 */
	public static HudRect sidebar(float screenWidth, float screenHeight, int contentWidth, int lines) {
		int shown = Math.clamp(lines, 0, 15);
		int height = shown * 9;
		float bottom = (float) Math.floor(screenHeight / 2.0F) + height / 3;
		float left = screenWidth - contentWidth - 5;
		return new HudRect(left, bottom - height - 10, screenWidth - 1 - left, height + 10);
	}

	/**
	 * Minecraft's effect icons in the top-right corner: 24-unit icons 25 units apart, helpful effects in a row from
	 * y 1 and harmful ones in a row below (both 15 lower in the demo).
	 */
	public static HudRect effects(float screenWidth, int beneficial, int harmful) {
		int columns = Math.max(Math.max(beneficial, harmful), 1);
		return new HudRect(screenWidth - 25 * columns, 1, 25 * columns - 1, harmful > 0 ? 50 : 24);
	}

	/** The hotbar's left edge: Minecraft halves the width in whole units first. */
	private static float centreLeft(float screenWidth) {
		return (float) Math.floor(screenWidth / 2.0F) - HOTBAR_WIDTH / 2;
	}
}
