package com.flintclient.client.platform;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.render.Canvas;
import org.jspecify.annotations.Nullable;

/**
 * The Minimap module's map, on Minecraft 1.8.9: Flint has no map of the world here yet (the module isn't listed on 1.8.9), so
 * there is never a map to draw. Platform class (see {@link Clock}).
 */
public final class HudMaps {
	private static final String[] NONE = new String[0];

	private HudMaps() {
	}

	public static boolean ready() {
		return false;
	}

	public static String[] info(ModuleState state) {
		return NONE;
	}

	public static String[] sampleInfo(ModuleState state) {
		return NONE;
	}

	public static @Nullable String summary() {
		return null;
	}

	public static boolean enlarged() {
		return false;
	}

	public static void drawEnlarged(Canvas c, ModuleState state, int screenW, int screenH, float unit) {
	}

	public static void drawSample(Canvas c, ModuleState state, int x, int y, int size, float unit) {
	}

	public static void draw(Canvas c, ModuleState state, int x, int y, int size, float unit, boolean hud) {
	}
}
