package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Tokens;
import org.jspecify.annotations.Nullable;

/**
 * How one module is drawn right now, in physical pixels: its style, size step and text faces, the spacing
 * and colours that follow from them, and its panel. Resolved from the module's appearance overrides over the
 * layout's shared style the same way {@link Appearance#effective} does, but into this reused object, so
 * resolving allocates nothing and previews can resolve per draw.
 *
 * <p>Metrics at size 1× in GUI units: a line of text is 7 units (Flint, Minimal) or Minecraft's 9; padding is
 * the appearance's padding across and two thirds of it down (Flint, Minimal) or two less than it (Classic, whose
 * line already has its leading), so a one-line module with the default padding of 3 is 11 units tall in every
 * style. Everything scales with the size step and lands on whole pixels.
 *
 * <p>The accent colour tints labels and units in every style ({@link HudColor#DEFAULT} keeps each style's own:
 * Flint's ember, Classic's grey, Minimal's dimmed text) and is the colour a pressed key takes unless the keystrokes
 * module picks its own ({@link #accent}).
 */
final class HudStyle {
	/** Flint's panel and the Minimal panel (when a module turns it on): the rail colour of the kit. */
	private static final int FLINT_PANEL = 0xFF0F0E0D;
	/** Flint's 1 px panel outline, white at about 6 %. */
	private static final int FLINT_OUTLINE = 0x10FFFFFF;
	private static final int CLASSIC_TEXT = 0xFFFFFFFF;
	/** Minecraft's grey for secondary text (as in its options and the "FPS:"-style labels of debug text). */
	private static final int CLASSIC_LABEL = 0xFFAAAAAA;
	/** Minimal's shadow: black at 60 %. */
	private static final int MINIMAL_SHADOW = 0x99000000;

	HudThemeId theme = HudThemeId.FLINT;
	int stepIndex = ScaleSteps.index(ScaleSteps.DEFAULT);
	float step = ScaleSteps.DEFAULT;
	/** Canvas pixels per GUI unit (the GUI scale on the HUD, the interface scale in previews). */
	int unitPx = 1;
	/** Pixels per module unit: the unit scale times the size step. */
	float u = 1;
	/** The canvas scale text is drawn at: {@link #unitPx}, or Classic's whole pixels per font pixel. */
	int textScale = 1;
	int valueFace;
	int labelFace;
	boolean showLabel = true;
	boolean background = true;
	int padX;
	int padY;
	int lineH;
	int lineGap;
	/** Between a label and its value, and between groups on one line. */
	int space;
	/** Between a value and its unit. */
	int thin;
	int radius;
	int outlinePx;
	int text;
	/** Labels and units: the accent colour, or the style's own label colour. */
	int label;
	/** What a pressed key fills with by default: the accent colour, or ember. */
	int accent;
	int muted;
	/** Dark ink for text on a light fill (an inverted key): the panel's colour, opaque. */
	int ink;
	int panel;
	int outline;
	/** Classic and Minimal draw a shadow under text, offset this many pixels (0: none). */
	int shadowPx;
	/**
	 * The shadow is Minecraft's own (the text colour at a quarter of its brightness), whatever the style: set by a module
	 * that chooses its own shadow (FPS); every {@link #resolve} clears it.
	 */
	boolean minecraftShadow;

	/**
	 * Resolves the style of a module with overrides {@code module} in a layout styled {@code layout}, shown as
	 * {@code override} when not null (setup previews), at {@code step} on a canvas of {@code unitPx} pixels per
	 * GUI unit.
	 */
	void resolve(Appearance layout, Appearance module, @Nullable HudThemeId override, float step, int unitPx) {
		HudThemeId style = override != null ? override : pick(module.theme(), layout.theme(), Appearance.DEFAULTS.theme());
		HudColor color = pick(module.textColor(), layout.textColor(), HudColor.DEFAULT);
		Boolean panelOn = pick(module.background(), layout.background(), style.defaultBackground());
		Integer opacity = pick(module.backgroundOpacity(), layout.backgroundOpacity(), style.defaultBackgroundOpacity());
		Integer padding = pick(module.padding(), layout.padding(), Appearance.PADDING.defaultValue());
		Boolean labels = pick(module.showLabel(), layout.showLabel(), Appearance.SHOW_LABEL.defaultValue());
		HudColor accentColor = pick(module.accentColor(), layout.accentColor(), HudColor.DEFAULT);
		set(style, color, panelOn, opacity, padding, labels, step, unitPx, accentColor);
	}

	/** Sets every field from resolved values, with the style's own accent (tests use it directly). */
	void set(HudThemeId style, HudColor color, boolean panelOn, int opacity, int padding, boolean labels, float step, int unitPx) {
		set(style, color, panelOn, opacity, padding, labels, step, unitPx, HudColor.DEFAULT);
	}

	/** Sets every field from resolved values. */
	void set(HudThemeId style, HudColor color, boolean panelOn, int opacity, int padding, boolean labels, float step, int unitPx,
		HudColor accentColor) {
		this.theme = style;
		this.minecraftShadow = false;
		this.stepIndex = ScaleSteps.index(step);
		this.step = ScaleSteps.step(stepIndex);
		this.unitPx = Math.max(1, unitPx);
		this.u = this.step * this.unitPx;
		this.showLabel = labels;
		this.background = panelOn;
		this.valueFace = HudFaces.index(style, HudFaces.VALUE, stepIndex);
		this.labelFace = HudFaces.index(style, HudFaces.LABEL, stepIndex);
		int pad = Math.clamp(padding, 0, 8);
		float alpha = Math.clamp(opacity, 0, 100) / 100.0F;
		this.accent = accentColor.isDefault() ? Tokens.ACCENT : accentColor.argb();
		switch (style) {
			case CLASSIC -> {
				textScale = Math.max(1, Math.round(u));
				lineH = 9 * textScale;
				lineGap = 0;
				padX = Math.round(pad * u);
				padY = Math.round(Math.max(0, pad - 2) * u);
				// One space of Minecraft's font everywhere, as "FPS: 144" and "42 ms" read in vanilla text.
				space = 4 * textScale;
				thin = space;
				radius = 0;
				outlinePx = 0;
				text = color.isDefault() ? CLASSIC_TEXT : color.argb();
				label = accentColor.isDefault() ? CLASSIC_LABEL : accentColor.argb();
				muted = CLASSIC_LABEL;
				panel = Palette.withAlpha(0xFF000000, alpha);
				ink = 0xFF000000;
				outline = 0;
				shadowPx = textScale;
			}
			case MINIMAL -> {
				metrics(pad);
				radius = Math.round(2 * u);
				outlinePx = 0;
				text = color.isDefault() ? Tokens.TEXT : color.argb();
				label = accentColor.isDefault() ? Palette.withAlpha(text, 0.72F) : accentColor.argb();
				muted = Palette.withAlpha(text, 0.72F);
				panel = Palette.withAlpha(FLINT_PANEL, alpha);
				ink = FLINT_PANEL;
				outline = 0;
				// The spec's 1-pixel shadow: one physical pixel at every GUI scale.
				shadowPx = 1;
			}
			default -> {
				metrics(pad);
				radius = Math.round(3 * u);
				outlinePx = Math.max(1, Math.round(this.unitPx / 4.0F));
				text = color.isDefault() ? Tokens.TEXT : color.argb();
				label = accentColor.isDefault() ? Tokens.ACCENT_TEXT : accentColor.argb();
				muted = Tokens.TEXT_DIM;
				panel = Palette.withAlpha(FLINT_PANEL, alpha);
				ink = FLINT_PANEL;
				outline = FLINT_OUTLINE;
				shadowPx = 0;
			}
		}
	}

	private void metrics(int pad) {
		textScale = unitPx;
		lineH = Math.round(7 * u);
		lineGap = Math.round(2 * u);
		padX = Math.round(pad * u);
		padY = Math.round(pad * 2 / 3.0F * u);
		space = Math.round(3 * u);
		thin = Math.max(1, Math.round(1.5F * u));
	}

	boolean classic() {
		return theme == HudThemeId.CLASSIC;
	}

	boolean minimal() {
		return theme == HudThemeId.MINIMAL;
	}

	/** Minimal writes units after values ("144 fps"); the others put labels first ("FPS 144", "FPS: 144"). */
	boolean unitsAfter() {
		return theme == HudThemeId.MINIMAL;
	}

	/** The shadow colour under text of colour {@code argb} (0 for none). */
	int shadowFor(int argb) {
		if (shadowPx == 0) {
			return 0;
		}
		if (theme == HudThemeId.CLASSIC || minecraftShadow) {
			// Minecraft's own text shadow: the colour at a quarter of its brightness, same alpha.
			int r = (argb >> 16 & 255) / 4;
			int g = (argb >> 8 & 255) / 4;
			int b = (argb & 255) / 4;
			return argb & 0xFF000000 | r << 16 | g << 8 | b;
		}
		return Palette.withAlpha(MINIMAL_SHADOW, (argb >>> 24) / 255.0F);
	}

	/** Pixels for {@code units} module units at this size. */
	int px(float units) {
		return Math.round(units * u);
	}

	private static <T> T pick(@Nullable T module, @Nullable T layout, T fallback) {
		return module != null ? module : layout != null ? layout : fallback;
	}
}
