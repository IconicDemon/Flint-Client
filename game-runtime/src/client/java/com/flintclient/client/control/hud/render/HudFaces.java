package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.platform.FontRef;
import com.flintclient.client.render.DigitRun;
import com.flintclient.client.render.FontFace;

/**
 * The HUD's typeface: Minecraft's own font ({@link #VANILLA}) in every style and role, drawn at a whole number of
 * pixels per font pixel so it stays pixel-crisp, as the owner asked for all of Flint's in-game text. The styles
 * differ in their panels and shadows ({@link HudStyle}), not their letters. Indexed per style, role and size step so
 * callers keep one face per slot. Render thread.
 */
final class HudFaces {
	static final int VALUE = 0;
	static final int LABEL = 1;
	static final int ROLES = 2;
	static final int STEPS = ScaleSteps.count();
	/** {@code values()} copies its array on every call; this one is read per draw. */
	private static final HudThemeId[] THEMES = HudThemeId.values();
	static final int COUNT = THEMES.length * ROLES * STEPS;
	/** Minecraft's font: an 8-pixel bitmap whose capitals are 7 pixels tall. */
	static final FontFace VANILLA = new VanillaFace();

	private static final FontFace[] FACES = new FontFace[COUNT];
	private static DigitRun vanillaDigits;

	private HudFaces() {
	}

	/** The index of a style's face for {@code role} at the size step with index {@code stepIndex}. */
	static int index(HudThemeId theme, int role, int stepIndex) {
		return (theme.ordinal() * ROLES + role) * STEPS + Math.clamp(stepIndex, 0, STEPS - 1);
	}

	static HudThemeId theme(int index) {
		return THEMES[index / (ROLES * STEPS)];
	}

	static int role(int index) {
		return index / STEPS % ROLES;
	}

	static float step(int index) {
		return ScaleSteps.step(index % STEPS);
	}

	static FontFace face(int index) {
		FontFace face = FACES[index];
		if (face == null) {
			face = create(index);
			FACES[index] = face;
		}
		return face;
	}

	/** Letter spacing (em) for text in this face. */
	static float tracking(int index) {
		return 0.0F;
	}

	/** Tabular digits in this face (one run for every style and size: it caches each pixel scale). */
	static DigitRun digits(int index) {
		if (vanillaDigits == null) {
			vanillaDigits = new DigitRun(VANILLA);
		}
		return vanillaDigits;
	}

	private static FontFace create(int index) {
		return VANILLA;
	}

	/**
	 * Minecraft's default font as a {@link FontFace}. It is a bitmap: the size can't change, only how many
	 * screen pixels draw each font pixel (the canvas scale), so it is crisp at whole scales only.
	 */
	private static final class VanillaFace implements FontFace {
		@Override
		public FontRef forScale(int scale) {
			return FontRef.MINECRAFT;
		}

		@Override
		public float size() {
			return 8.0F;
		}

		@Override
		public float capHeightPx(int scale) {
			return 7.0F * scale;
		}

		@Override
		public boolean covers(String text) {
			return true;
		}

		@Override
		public String toString() {
			return "minecraft:default";
		}
	}
}
