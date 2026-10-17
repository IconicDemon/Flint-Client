package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import org.jspecify.annotations.Nullable;

/**
 * A button with an optional icon before its label. Pills are 20 units tall (or as tall as they are placed) and fully rounded: PRIMARY is ember
 * with dark ink (the one main action of a view), SECONDARY a strong hairline outline with light text, DANGER a
 * dark crimson outline for destructive confirmations. Text buttons have no surface: GHOST is muted and brightens
 * on hover, ACCENT is ember text, DANGER_TEXT red text. Disabled buttons fade their content; the reason is the
 * tooltip and is read out.
 */
public class Button extends Pressable {
	public enum Variant {
		/** Ember pill, dark ink: the main action. */
		PRIMARY,
		/** A hairline outline pill with light text. */
		SECONDARY,
		/** Text only, muted; brightens on hover. */
		GHOST,
		/** Text only, in ember. */
		ACCENT,
		/** A dark crimson pill with red text: destructive confirmations in dialogs. */
		DANGER,
		/** Text only, in red: an inline destructive confirmation ("Reset"). */
		DANGER_TEXT;

		boolean pill() {
			return this == PRIMARY || this == SECONDARY || this == DANGER;
		}
	}

	public static final float HEIGHT = 20;
	/** Text buttons' height (their hit target; the text is centred in it). */
	public static final float TEXT_HEIGHT = 16;
	private static final float PAD_X = 12;
	private static final float ICON_GAP = 4;

	private final Variant variant;
	private final TextLine label;
	private @Nullable Icon icon;
	private @Nullable Icon trailingIcon;
	private float iconSize = 8;

	public Button(String label, Variant variant, @Nullable Runnable action) {
		this(label, variant, variant.pill() ? Type.BUTTON : Type.LABEL, action);
	}

	/** A button whose label uses {@code face} (text buttons in a row of other text). */
	public Button(String label, Variant variant, FontFace face, @Nullable Runnable action) {
		super(action);
		this.variant = variant;
		this.label = new TextLine(face, label);
		this.h = variant.pill() ? HEIGHT : TEXT_HEIGHT;
	}

	public Button icon(@Nullable Icon icon) {
		this.icon = icon;
		invalidate();
		return this;
	}

	/** An icon after the label (a chevron on "Modules ›"). */
	public Button trailingIcon(@Nullable Icon icon) {
		this.trailingIcon = icon;
		invalidate();
		return this;
	}

	public Button iconSize(float units) {
		this.iconSize = units;
		invalidate();
		return this;
	}

	public void setLabel(String text) {
		label.set(text);
		invalidate();
	}

	public String label() {
		return label.text();
	}

	public Variant variant() {
		return variant;
	}

	private float padX() {
		return variant.pill() ? PAD_X : 0;
	}

	@Override
	public float prefWidth() {
		Canvas c = measure();
		float text = label.text().isEmpty() ? 0 : (float) c.width(label) / c.scale();
		float icons = (icon == null ? 0 : iconSize + (text > 0 ? ICON_GAP : 0)) + (trailingIcon == null ? 0 : iconSize * 0.75F + ICON_GAP);
		return (float) Math.ceil(2 * padX() + text + icons);
	}

	@Override
	public float prefHeight(float width) {
		return variant.pill() ? HEIGHT : TEXT_HEIGHT;
	}

	@Override
	public String narration() {
		return withReason(label.text() + ", button");
	}

	@Override
	protected void paint(Canvas c) {
		int x0 = px0(c);
		int y0 = py0(c);
		int pw = pxW(c);
		int ph = pxH(c);
		int fill = 0;
		int border = 0;
		int ink;
		float lift = Math.max(hover, focus);
		switch (variant) {
			case PRIMARY -> {
				fill = Palette.lerp(Palette.lerp(Tokens2.EMBER, Tokens2.EMBER_HOVER, hover), Tokens2.EMBER_PRESS, press);
				ink = Tokens2.INK;
			}
			case SECONDARY -> {
				fill = Palette.lerp(Palette.withAlpha(Tokens2.HOVER, hover), Tokens2.PRESS, press);
				border = Palette.lerp(Tokens2.hairlineStrong(), 0x30FFFFFF, hover);
				ink = Palette.lerp(Tokens2.TEXT_DIM, Tokens2.TEXT, lift);
			}
			case DANGER -> {
				fill = Palette.lerp(Palette.lerp(Palette.DANGER_SURFACE, Palette.DANGER_SURFACE_HOVER, hover), Palette.DANGER_SURFACE, press);
				border = Palette.lerp(Palette.DANGER_BORDER, Palette.DANGER_BORDER_HOVER, lift);
				ink = Palette.lerp(Palette.DANGER_TEXT, Palette.DANGER_TEXT_HOVER, hover);
			}
			case ACCENT -> ink = Palette.lerp(Tokens2.EMBER_TEXT, 0xFFF5B060, lift);
			case DANGER_TEXT -> ink = Palette.lerp(Tokens2.DANGER, 0xFFF0907F, lift);
			default -> ink = Palette.lerp(Tokens2.muted(), Tokens2.TEXT, lift);
		}
		if (!enabled()) {
			if (variant == Variant.PRIMARY) {
				fill = Palette.lerp(Tokens2.EMBER, 0xFF2A2724, 0.6F);
				ink = Palette.withAlpha(Tokens2.INK, 0.7F);
			} else {
				ink = Palette.withAlpha(Tokens2.muted(), 0.5F);
				border = border == 0 ? 0 : Tokens2.hairline();
			}
		}
		if (variant.pill()) {
			c.roundRect(x0, y0, pw, ph, ph / 2, fill);
			if (border != 0) {
				c.ring(x0, y0, pw, ph, ph / 2, 1, border);
			}
		}
		int iconPx = icon == null ? 0 : c.px(iconSize);
		int trailPx = trailingIcon == null ? 0 : c.px(iconSize * 0.75F);
		boolean hasText = !label.text().isEmpty();
		int gap = c.px(ICON_GAP);
		int pad = c.px(padX());
		int maxW = Math.max(0, pw - 2 * pad - iconPx - trailPx - (iconPx > 0 && hasText ? gap : 0) - (trailPx > 0 ? gap : 0));
		int textW = hasText ? c.width(label, maxW) : 0;
		int groupW = iconPx + (iconPx > 0 && hasText ? gap : 0) + textW + (trailPx > 0 ? gap + trailPx : 0);
		int left = variant.pill() ? x0 + (pw - groupW) / 2 : x0;
		int center = y0 + ph / 2;
		if (icon != null) {
			c.icon(icon, left, center - iconPx / 2, iconPx, ink);
			left += iconPx + (hasText ? gap : 0);
		}
		if (hasText) {
			c.text(label, left, c.baselineFor(label, center), ink, maxW);
			left += textW;
		}
		if (trailingIcon != null) {
			c.icon(trailingIcon, left + gap, center - trailPx / 2, trailPx, ink);
		}
		if (variant.pill()) {
			Kit.focusRing(c, x, y, w, h, h / 2, focus);
		} else {
			Kit.focusRing(c, x - 2, y + 1, w + 4, h - 2, 3, focus);
		}
	}
}
