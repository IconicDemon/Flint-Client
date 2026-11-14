package com.flintclient.client.ui.widget;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** A main-menu button: icon and label centred on a rounded surface, in one of three styles. */
public final class MenuButton extends FlintButton {
	public enum Style {
		/** Dark neutral surface (Singleplayer, Multiplayer, ...). */
		NEUTRAL,
		/** Flint orange, the primary accent (Store). */
		ACCENT,
		/** Subdued crimson for leaving the game (Quit Game). */
		DANGER
	}

	/** Corner radius and icon size in GUI units. */
	public static final float RADIUS = 2.5F;
	private static final float ICON = 4.6F;
	private static final float ICON_GAP = 2.2F;

	private final Style style;
	private final Icon icon;
	private final TextLine label;
	/** A count shown at the button's right (Discover's partner servers); none while it is 0. */
	private java.util.function.IntSupplier count = () -> 0;
	private final TextLine countText = new TextLine(com.flintclient.client.render.PixelFace.of(FlintFonts.SMALL.size), "");
	private int shownCount;

	public MenuButton(Component message, Icon icon, Style style, Runnable action) {
		super(message, action);
		this.style = style;
		this.icon = icon;
		String text = message.getString();
		this.label = switch (style) {
			case NEUTRAL -> new TextLine(FlintFonts.LABEL, text);
			// The accented buttons use tracked capitals, as in the reference design.
			case ACCENT -> new TextLine(FlintFonts.STRONG, text.toUpperCase(Locale.ROOT), 0.16F);
			case DANGER -> new TextLine(FlintFonts.STRONG, text.toUpperCase(Locale.ROOT), 0.18F);
		};
	}

	/** Shows {@code count} in a small pill at the button's right (in Minecraft's font), when it is above 0. */
	public MenuButton count(java.util.function.IntSupplier count) {
		this.count = count;
		return this;
	}

	@Override
	public void setMessage(Component message) {
		super.setMessage(message);
		if (label != null) {
			String text = message.getString();
			label.set(style == Style.NEUTRAL ? text : text.toUpperCase(Locale.ROOT));
		}
	}

	@Override
	protected void paint(Canvas c) {
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		int r = c.px(RADIUS);
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		float dim = active ? 1.0F : 0.45F;

		int fill;
		int border;
		int ink;
		switch (style) {
			case ACCENT -> {
				fill = Palette.lerp(Palette.lerp(Palette.ACCENT, Palette.ACCENT_HOVER, hover), Palette.ACCENT_PRESS, press);
				border = Palette.lerp(fill, Palette.TEXT, focus);
				ink = Palette.ACCENT_INK;
			}
			case DANGER -> {
				fill = Palette.lerp(Palette.lerp(Palette.DANGER_SURFACE, Palette.DANGER_SURFACE_HOVER, hover), Palette.SURFACE_PRESS, press * 0.6F);
				border = Palette.lerp(Palette.lerp(Palette.DANGER_BORDER, Palette.DANGER_BORDER_HOVER, hover), Palette.DANGER_TEXT, focus);
				ink = Palette.lerp(Palette.DANGER_TEXT, Palette.DANGER_TEXT_HOVER, hover);
			}
			default -> {
				fill = Palette.lerp(Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, hover), Palette.SURFACE_PRESS, press);
				border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover), Palette.ACCENT, focus * 0.85F);
				ink = Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, Math.max(hover, focus));
			}
		}
		if (!active) {
			// Unavailable: the surface stays solid; only the content fades.
			fill = style == Style.ACCENT ? Palette.lerp(Palette.ACCENT, Palette.SURFACE, 0.55F) : fill;
			border = style == Style.ACCENT ? fill : border;
			ink = Palette.withAlpha(ink, dim);
		}
		int focusPx = focus > 0.5F ? borderPx : 0;
		c.panel(x, y, w, h, r, borderPx + focusPx, border, fill);

		int iconPx = c.px(ICON);
		int gap = icon == null ? 0 : c.px(ICON_GAP);
		int textW = c.width(label);
		int groupW = (icon == null ? 0 : iconPx + gap) + textW;
		int left = x + (w - groupW) / 2;
		int center = y + h / 2;
		if (icon != null) {
			c.icon(icon, left, center - iconPx / 2, iconPx, ink);
		}
		c.text(label, left + (icon == null ? 0 : iconPx + gap), c.baselineFor(label, center), ink);
		int n = count.getAsInt();
		if (n > 0) {
			if (n != shownCount) {
				shownCount = n;
				countText.set(n > 999 ? "999+" : Integer.toString(n));
			}
			int ph = c.px(6.6F);
			int pw = Math.max(ph, c.width(countText) + c.px(3.6F));
			int px = x + w - c.px(3.4F) - pw;
			c.roundRect(px, center - ph / 2, pw, ph, ph / 2, Palette.withAlpha(Palette.TEXT, 0.10F + 0.06F * hover));
			c.text(countText, px + (pw - c.width(countText)) / 2, c.baselineFor(countText, center), Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, hover));
		}
	}
}
