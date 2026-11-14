package com.flintclient.client.control.feature;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.Crosshair;
import java.util.List;

/**
 * Crosshair's ready-made shapes, one click each on its page: the style and its size, gap, thickness, turn, outline and dot. The
 * player's colour, opacity, adaptive colour, scale and offset stay theirs, and so does their drawing (no preset draws over it),
 * so a preset is a starting point to tweak. Every one is made of whole GUI units, so it stays sharp at any GUI scale. Pure.
 */
public final class CrosshairPresets {
	/** Crosshair's styles ({@link Crosshair#TYPE}'s indices) the presets use. */
	private static final int CROSS = 1;
	private static final int CIRCLE = 2;
	private static final int SQUARE = 3;
	private static final int TRIANGLE = 4;
	private static final int CHEVRON = 5;

	/** One preset: its name and the shape it sets. */
	public record Preset(String name, int type, int width, int height, int gap, int thickness, int rotation, boolean outline, boolean dot) {
		/** Sets the shape on {@code s} (its other settings stay); whether anything changed. */
		public boolean apply(ModuleState s) {
			boolean changed = s.set(Crosshair.TYPE, type);
			changed |= s.set(Crosshair.WIDTH, width);
			changed |= s.set(Crosshair.HEIGHT, height);
			changed |= s.set(Crosshair.GAP, gap);
			changed |= s.set(Crosshair.THICKNESS, thickness);
			changed |= s.set(Crosshair.ROTATION, rotation);
			changed |= s.set(Crosshair.OUTLINE, outline);
			changed |= s.set(Crosshair.DOT, dot);
			return changed;
		}

		/** Whether {@code s} has this shape now. */
		public boolean matches(ModuleState s) {
			return s.option(Crosshair.TYPE) == type && s.integer(Crosshair.WIDTH) == width && s.integer(Crosshair.HEIGHT) == height
				&& s.integer(Crosshair.GAP) == gap && s.integer(Crosshair.THICKNESS) == thickness && s.integer(Crosshair.ROTATION) == rotation
				&& s.bool(Crosshair.OUTLINE) == outline && s.bool(Crosshair.DOT) == dot;
		}
	}

	/** The presets, in the order the page shows them. */
	public static final List<Preset> ALL = List.of(
		new Preset("Classic", CROSS, 3, 3, 2, 1, 0, false, false),
		new Preset("Small plus", CROSS, 2, 2, 1, 1, 0, false, false),
		new Preset("Gapped plus", CROSS, 4, 4, 4, 1, 0, false, false),
		new Preset("Bold plus", CROSS, 4, 4, 2, 2, 0, true, false),
		new Preset("Plus and dot", CROSS, 3, 3, 3, 1, 0, false, true),
		new Preset("Dot", SQUARE, 0, 0, 0, 1, 0, false, false),
		new Preset("Circle", CIRCLE, 3, 3, 4, 1, 0, false, false),
		new Preset("Circle and dot", CIRCLE, 3, 3, 5, 1, 0, false, true),
		new Preset("X", CROSS, 3, 3, 2, 1, 45, false, false),
		new Preset("Chevron", CHEVRON, 4, 4, 1, 1, 0, false, false),
		new Preset("Triangle", TRIANGLE, 6, 6, 1, 1, 0, false, false),
		new Preset("Box", SQUARE, 2, 2, 1, 1, 0, false, false));

	private CrosshairPresets() {
	}
}
