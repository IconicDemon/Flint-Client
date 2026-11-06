package com.flintclient.client.platform;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.platform.Mc263Maps;
import com.flintclient.client.control.platform.Mc263Minimap;
import com.flintclient.client.render.Canvas;
import org.jspecify.annotations.Nullable;

/**
 * The Minimap module's map: the engine's map of the world drawn on a {@link Canvas}, its info lines and summary, and the
 * enlarged map while its key is held. Platform class (see {@link Clock}). Render thread.
 */
public final class HudMaps {
	private HudMaps() {
	}

	/** Whether there is a map to draw (a world, the map scanned). */
	public static boolean ready() {
		return Mc263Minimap.ready();
	}

	/** The info lines under the map (live), or a sample's. */
	public static String[] info(ModuleState state) {
		return Mc263Minimap.info(state);
	}

	public static String[] sampleInfo(ModuleState state) {
		return Mc263Minimap.sampleInfo(state);
	}

	public static @Nullable String summary() {
		return Mc263Minimap.summary();
	}

	/** Whether its enlarge key is held (the map grows in the middle of the screen). */
	public static boolean enlarged() {
		return Mc263Maps.enlarged();
	}

	public static void drawEnlarged(Canvas c, ModuleState state, int screenW, int screenH, float unit) {
		Mc263Minimap.drawEnlarged(c, state, screenW, screenH, unit);
	}

	public static void drawSample(Canvas c, ModuleState state, int x, int y, int size, float unit) {
		Mc263Minimap.drawSample(c, state, x, y, size, unit);
	}

	public static void draw(Canvas c, ModuleState state, int x, int y, int size, float unit, boolean hud) {
		Mc263Minimap.draw(c, state, x, y, size, unit, hud);
	}
}
