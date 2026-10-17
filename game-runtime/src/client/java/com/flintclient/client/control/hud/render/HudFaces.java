package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.render.DigitRun;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.ScaledFont;
import net.minecraft.network.chat.FontDescription;

/**
 * The HUD's typefaces: per style, a value face and a smaller label face at each size step, so the number of
 * font sets stays fixed (3 styles × 2 roles × 6 steps at most, far below {@link ScaledFont#MAX_FACES}).
 * <ul>
 * <li>Flint: Inter SemiBold, values at {@value #VALUE_EM} units and labels at {@value #FLINT_LABEL_EM}, tracked
 * like small capitals.</li>
 * <li>Minimal: Inter Medium, values at {@value #VALUE_EM} and units at {@value #MINIMAL_LABEL_EM}.</li>
 * <li>Classic: Minecraft's own font ({@link #VANILLA}) for both, drawn at a whole number of pixels per font
 * pixel so it stays pixel-crisp.</li>
 * </ul>
 * Faces and their digit runs are made the first time a style and size is drawn, then kept. Render thread.
 */
final class HudFaces {
	static final int VALUE = 0;
	static final int LABEL = 1;
	static final int ROLES = 2;
	static final int STEPS = ScaleSteps.count();
	/** {@code values()} copies its array on every call; this one is read per draw. */
	private static final HudThemeId[] THEMES = HudThemeId.values();
	static final int COUNT = THEMES.length * ROLES * STEPS;
	/** Em sizes in GUI units at size 1×. A value line is 7 units tall, so a default module with padding is 11. */
	static final float VALUE_EM = 7.0F;
	static final float FLINT_LABEL_EM = 5.0F;
	static final float MINIMAL_LABEL_EM = 5.4F;
	/** Flint's labels are tracked a little, as small capitals are. */
	static final float LABEL_TRACKING = 0.06F;
	/** Minecraft's font (for the Classic style): an 8-pixel bitmap whose capitals are 7 pixels tall. */
	static final FontFace VANILLA = new VanillaFace();

	private static final FontFace[] FACES = new FontFace[COUNT];
	private static final DigitRun[] DIGITS = new DigitRun[COUNT];
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
		return theme(index) == HudThemeId.FLINT && role(index) == LABEL ? LABEL_TRACKING : 0.0F;
	}

	/** Tabular digits in this face (shared by every Classic size: one run caches each pixel scale). */
	static DigitRun digits(int index) {
		if (theme(index) == HudThemeId.CLASSIC) {
			if (vanillaDigits == null) {
				vanillaDigits = new DigitRun(VANILLA);
			}
			return vanillaDigits;
		}
		DigitRun run = DIGITS[index];
		if (run == null) {
			run = new DigitRun(face(index));
			DIGITS[index] = run;
		}
		return run;
	}

	private static FontFace create(int index) {
		float step = step(index);
		boolean label = role(index) == LABEL;
		return switch (theme(index)) {
			case FLINT -> ScaledFont.of(FlintFonts.STRONG, (label ? FLINT_LABEL_EM : VALUE_EM) * step);
			case MINIMAL -> ScaledFont.of(FlintFonts.LABEL, (label ? MINIMAL_LABEL_EM : VALUE_EM) * step);
			case CLASSIC -> VANILLA;
		};
	}

	/**
	 * Minecraft's default font as a {@link FontFace}. It is a bitmap: the size can't change, only how many
	 * screen pixels draw each font pixel (the canvas scale), so it is crisp at whole scales only.
	 */
	private static final class VanillaFace implements FontFace {
		@Override
		public FontDescription forScale(int scale) {
			return FontDescription.DEFAULT;
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
