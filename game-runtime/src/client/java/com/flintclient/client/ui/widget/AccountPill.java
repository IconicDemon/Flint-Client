package com.flintclient.client.ui.widget;

import com.flintclient.client.module.account.AccountSkin;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.network.chat.Component;

/** The signed-in player: their skin's face and their Minecraft name, from the current game session. */
public final class AccountPill extends FlintButton {
	public static final float HEIGHT = 13.0F;
	private static final float PAD = 2.2F;
	private static final float AVATAR = 9.0F;
	private static final float GAP = 3.2F;

	private final TextLine name = new TextLine(FlintFonts.STRONG, "");

	public AccountPill(Component message, Runnable action) {
		super(message, action);
	}

	/** Width in GUI units that fits the current name (the screen lays the pill out with it). */
	public int preferredWidth(Canvas c) {
		name.set(AccountSkin.username());
		// Minecraft names are at most 16 characters, so the pill always fits the whole name.
		float nameGui = c.width(name) / (float) c.scale();
		return Math.round(PAD + AVATAR + GAP + nameGui + PAD + 2.4F);
	}

	@Override
	protected void paint(Canvas c) {
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int fill = Palette.lerp(Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, hover), Palette.SURFACE_PRESS, press);
		int border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, hover), Palette.ACCENT, focus * 0.85F);
		c.panel(x, y, w, h, c.px(MenuButton.RADIUS), borderPx + (focus > 0.5F ? borderPx : 0), border, fill);

		// Skin pixels stay sharp only at whole multiples of the 8-pixel face.
		int avatar = Math.max(8, Math.round(c.px(AVATAR) / 8.0F) * 8);
		int ax = x + c.px(PAD);
		int ay = y + (h - avatar) / 2;
		PlayerFaceExtractor.extractRenderState(c.graphics(), AccountSkin.current(), ax, ay, avatar);
		// The pill sits on the dark top of the artwork, so its visible colour is the fill over that tone.
		c.roundCorners(ax, ay, avatar, avatar, c.px(1.6F), Palette.over(fill, Palette.BACKGROUND_TOP));

		name.set(AccountSkin.username());
		int textX = ax + avatar + c.px(GAP);
		int ink = Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, Math.max(hover, focus));
		c.text(name, textX, c.baselineFor(name, y + h / 2), ink);
	}
}
