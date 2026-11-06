package com.flintclient.client.control.platform;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MapMods.Minimap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Your arrow on the minimap and the world map, shaped and lit as Xaero's: a broad arrowhead (as wide as it is long, its
 * back cut in a shallow notch) turned on the notch, which is where you stand, so the point runs ahead of you. Under it, two
 * dark copies a pixel above and a pixel below, which give it Xaero's soft edge on any terrain. Off the world map's screen,
 * a flat chevron waits on the edge, pointing your way. Drawn by Flint as filled shapes (no texture).
 *
 * <p>Sizes are in "arrow units": the shape is 26 across and 26 long (the tip 20 ahead of the notch, the wings 6 behind it).
 * The minimap draws a unit at a quarter of a GUI pixel times the arrow scale (Xaero's 1.5 by default); the world map draws
 * one at a screen pixel (more on screens over 1100 pixels tall, as Xaero's). Render thread; no allocation.
 */
public final class MapArrow {
	/** Xaero's arrow colours, by name, then your scoreboard team's (Xaero's "Team"). */
	public static final int[] COLORS = {0xCC1A1A, 0x179100, 0x008CFF, 0xFFED00, 0xBA54D4, 0xFFFFFF, 0x000000, 0x750000};
	private static final float[] SHAPE = new float[12];

	private MapArrow() {
	}

	/**
	 * The arrow's colour as the minimap's settings choose it: a named colour, your team's (the first colour when you're on
	 * none) or the custom one.
	 */
	public static int color(ModuleState s, @Nullable Player player) {
		int choice = s.option(Minimap.ARROW_COLOR);
		if (choice < COLORS.length) {
			return COLORS[choice];
		}
		if (choice == COLORS.length) {
			int team = teamColor(player);
			return team < 0 ? COLORS[0] : team;
		}
		return s.rgb(Minimap.ARROW_CUSTOM);
	}

	/** {@code player}'s scoreboard team colour, or -1 without a coloured team. */
	static int teamColor(@Nullable Player player) {
		if (player == null || player.getTeam() == null) {
			return -1;
		}
		return player.getTeamColor() & 0xFFFFFF;
	}

	/**
	 * The arrow at ({@code x}, {@code y}) pointing along ({@code fx}, {@code fy}) (a unit vector, screen pixels, y down),
	 * {@code unit} pixels per arrow unit, in {@code rgb} at {@code opacity} (0..1); its dark copies {@code shadow} pixels
	 * above and below, at {@code above} and {@code below} opacity.
	 */
	static void draw(GuiGraphicsExtractor g, float x, float y, float fx, float fy, float unit, int rgb, float opacity, float shadow,
		float above, float below) {
		// The dark copies lean toward black as the arrow gets more opaque (Xaero's: its colour at no opacity).
		int dark = darken(rgb, opacity);
		head(g, x, y - shadow, fx, fy, unit, alpha(above) | dark);
		head(g, x, y + shadow, fx, fy, unit, alpha(below) | dark);
		head(g, x, y, fx, fy, unit, alpha(opacity) | rgb & 0xFFFFFF);
	}

	/** The minimap's arrow: a quarter GUI pixel per unit times the scale; shadows a GUI pixel off at 0.3 and 0.5 of opacity². */
	static void minimap(GuiGraphicsExtractor g, ModuleState s, @Nullable Player player, float x, float y, float fx, float fy, float guiPixel) {
		float opacity = s.integer(Minimap.ARROW_OPACITY) / 100.0F;
		float unit = guiPixel * 0.25F * s.decimal(Minimap.ARROW_SCALE);
		draw(g, x, y, fx, fy, unit, color(s, player), opacity, guiPixel, opacity * opacity * 0.3F, opacity * opacity * 0.5F);
	}

	/** The world map's arrow: {@code unit} pixels per arrow unit; shadows two units off, at 0.4 above and 0.9 below. */
	static void worldMap(GuiGraphicsExtractor g, float x, float y, float fx, float fy, float unit, int rgb) {
		draw(g, x, y, fx, fy, unit, rgb, 1, 2 * unit, 0.4F, 0.9F);
	}

	/** The arrowhead: tip, right wing, notch, left wing (the tip first: the fan's centre). */
	private static void head(GuiGraphicsExtractor g, float x, float y, float fx, float fy, float unit, int argb) {
		if (argb >>> 24 == 0) {
			return;
		}
		// Right of the facing on screen (y down): (-fy, fx).
		float rx = -fy;
		float ry = fx;
		put(0, x, y, fx, fy, rx, ry, 0, 20 * unit);
		put(1, x, y, fx, fy, rx, ry, 13 * unit, -6 * unit);
		put(2, x, y, fx, fy, rx, ry, 0, -0.5F * unit);
		put(3, x, y, fx, fy, rx, ry, -13 * unit, -6 * unit);
		Mc263MapDraw.shape(g, SHAPE, 4, argb, null);
	}

	/**
	 * The far arrow (Xaero's chevron for a player off the map): its point at ({@code x}, {@code y}), pointing along
	 * ({@code fx}, {@code fy}); 54 units across, 13 deep, its back cut in two notches beside a small middle spike.
	 */
	static void far(GuiGraphicsExtractor g, float x, float y, float fx, float fy, float unit, int rgb) {
		farShape(g, x, y - 2 * unit, fx, fy, unit, 0x66000000);
		farShape(g, x, y + 2 * unit, fx, fy, unit, 0xE6000000);
		farShape(g, x, y, fx, fy, unit, 0xFF000000 | rgb & 0xFFFFFF);
	}

	private static void farShape(GuiGraphicsExtractor g, float x, float y, float fx, float fy, float unit, int argb) {
		float rx = -fy;
		float ry = fx;
		// The point, then round its back: right wing, right notch, the spike, left notch, left wing.
		put(0, x, y, fx, fy, rx, ry, 0, 0);
		put(1, x, y, fx, fy, rx, ry, 27 * unit, -13 * unit);
		put(2, x, y, fx, fy, rx, ry, 3.5F * unit, -9.5F * unit);
		put(3, x, y, fx, fy, rx, ry, 0, -12.7F * unit);
		put(4, x, y, fx, fy, rx, ry, -3.5F * unit, -9.5F * unit);
		put(5, x, y, fx, fy, rx, ry, -27 * unit, -13 * unit);
		Mc263MapDraw.shape(g, SHAPE, 6, argb, null);
	}

	/** Point {@code i}: {@code side} units to the right and {@code ahead} units forward of ({@code x}, {@code y}). */
	private static void put(int i, float x, float y, float fx, float fy, float rx, float ry, float side, float ahead) {
		SHAPE[2 * i] = x + side * rx + ahead * fx;
		SHAPE[2 * i + 1] = y + side * ry + ahead * fy;
	}

	private static int alpha(float a) {
		return Math.clamp(Math.round(a * 255), 0, 255) << 24;
	}

	private static int darken(int rgb, float toward) {
		float k = 1 - Math.clamp(toward, 0, 1);
		return Math.round((rgb >> 16 & 255) * k) << 16 | Math.round((rgb >> 8 & 255) * k) << 8 | Math.round((rgb & 255) * k);
	}
}
