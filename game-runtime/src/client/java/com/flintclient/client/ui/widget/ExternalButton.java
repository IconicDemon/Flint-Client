package com.flintclient.client.ui.widget;

import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.PixelFace;
import com.flintclient.client.render.TextLine;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A square inside the main menu's top-right row (after Settings, before Utilities) for another mod's own title-screen
 * button (Flashback's replays, Mod Menu's Mods…), drawn as the row's own icons are, so it reads as part of the row:
 * Flint's dark rounded tile, its thin border lighting on hover, and the mod's icon as a white mark ({@code render.WhiteMark})
 * in the row's grey, white under the pointer (its initial in Minecraft's font when it has no icon). "Flashback · External" is its tooltip. Pressing it presses that mod's button, which opens what the mod opens.
 */
public final class ExternalButton extends FlintButton {
	/** The mark's box in the square, in menu units (a little larger than the row's line icons: a mod's icon keeps a margin). */
	private static final float MARK = 7.2F;

	private final @Nullable Texture icon;
	private final int iconW;
	private final int iconH;
	private final TextLine initial = new TextLine(PixelFace.of(FlintFonts.LABEL.size), "");

	public ExternalButton(String name, @Nullable Texture icon, int iconW, int iconH, Runnable action) {
		super(Component.literal(name + " · External"), action);
		this.icon = icon;
		this.iconW = iconW;
		this.iconH = iconH;
		initial.set(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT));
		tooltip(Component.literal(name + " · External"));
	}

	@Override
	protected void paint(Canvas c) {
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		// As IconButton (the row's Friends, Messages, Settings and Apps).
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int fill = Palette.lerp(Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, hover), Palette.SURFACE_PRESS, press);
		int border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover), Palette.ACCENT, focus * 0.85F);
		int ink = active ? Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, Math.max(hover, focus)) : Palette.withAlpha(Palette.MUTED, 0.55F);
		c.panel(x, y, w, h, c.px(MenuButton.RADIUS), borderPx + (focus > 0.5F ? borderPx : 0), border, fill);
		if (icon != null && iconW > 0 && iconH > 0) {
			int box = Math.min(c.px(MARK), Math.min(w, h));
			float s = Math.min(box / (float) iconW, box / (float) iconH);
			int dw = Math.max(1, Math.round(iconW * s));
			int dh = Math.max(1, Math.round(iconH * s));
			c.texture(icon, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, iconW, iconH, ink);
		} else {
			c.text(initial, x + (w - c.width(initial)) / 2, c.baselineFor(initial, y + h / 2), ink);
		}
	}
}
