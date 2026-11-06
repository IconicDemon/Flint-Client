package com.flintclient.client.ui.widget;

import com.flintclient.client.module.account.AccountSkin;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Motion;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.network.chat.Component;

/**
 * The signed-in player: their skin's face, their Minecraft name (from the current game session) and a chevron that turns as
 * the account card opens, as the menu choice's pill does.
 */
public final class AccountPill extends FlintButton {
	public static final float HEIGHT = 13.0F;
	private static final float PAD = 2.2F;
	private static final float AVATAR = 9.0F;
	private static final float GAP = 3.2F;
	private static final float CHEVRON = 4.4F;
	private static final float CHEVRON_GAP = 2.6F;
	private static final float TURN_MILLIS = 180.0F;

	private final TextLine name = new TextLine(FlintFonts.STRONG, "");
	private final BooleanSupplier open;
	private float turned;
	private float openLit;

	public AccountPill(Component message, BooleanSupplier open, Runnable action) {
		super(message, action);
		this.open = open;
	}

	/** Width in GUI units that fits the current name (the screen lays the pill out with it). */
	public int preferredWidth(Canvas c) {
		name.set(AccountSkin.username());
		// Minecraft names are at most 16 characters, so the pill always fits the whole name.
		float nameGui = c.width(name) / c.unitScale();
		return Math.round(PAD + AVATAR + GAP + nameGui + CHEVRON_GAP + CHEVRON + PAD + 1.4F);
	}

	@Override
	protected void paint(Canvas c) {
		boolean isOpen = open.getAsBoolean();
		turned = Motion.toward(turned, isOpen ? 1.0F : 0.0F, TURN_MILLIS);
		openLit = Motion.toward(openLit, isOpen ? 1.0F : 0.0F, HOVER_MILLIS);
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		boolean zoomed = press > 0.01F;
		if (zoomed) {
			c.pushZoom(1.0F - 0.04F * press, x + w / 2.0F, y + h / 2.0F);
		}
		float lift = Math.max(hover, Motion.smooth(openLit));
		int borderPx = Math.max(1, Math.round(c.scale() / 4.0F));
		int fill = Palette.lerp(Palette.lerp(Palette.SURFACE, Palette.SURFACE_HOVER, lift), Palette.SURFACE_PRESS, press);
		int border = Palette.lerp(Palette.lerp(Palette.BORDER, Palette.BORDER_HOVER, lift), Palette.ACCENT, focus * 0.85F);
		c.panel(x, y, w, h, c.px(MenuButton.RADIUS), borderPx + (focus > 0.5F ? borderPx : 0), border, fill);

		// Skin pixels stay sharp only at whole multiples of the 8-pixel face.
		int avatar = Math.max(8, Math.round(c.px(AVATAR) / 8.0F) * 8);
		int ax = x + c.px(PAD);
		int ay = y + (h - avatar) / 2;
		// Minecraft draws the face at full strength whatever the fade, so while the pill fades away (the menus blending) it
		// goes once the pill is half gone.
		if (c.opacity() >= 0.5F) {
			PlayerFaceExtractor.extractRenderState(c.frame().graphics(), AccountSkin.current(), ax, ay, avatar);
			// The pill sits on the dark top of the artwork, so its visible colour is the fill over that tone.
			c.roundCorners(ax, ay, avatar, avatar, c.px(1.6F), Palette.over(fill, Palette.BACKGROUND_TOP));
		}

		name.set(AccountSkin.username());
		int textX = ax + avatar + c.px(GAP);
		int ink = Palette.lerp(Palette.TEXT_DIM, Palette.TEXT, Math.max(lift, focus));
		c.text(name, textX, c.baselineFor(name, y + h / 2), ink);

		int size = c.px(CHEVRON);
		int cx = x + w - c.px(PAD + 0.6F) - size;
		int cy = y + (h - size) / 2;
		int chevronInk = Palette.lerp(Palette.MUTED, Palette.TEXT, Math.max(lift, focus));
		float angle = (float) Math.PI * Motion.smooth(turned);
		if (angle > 0.001F) {
			var pose = c.frame().graphics().pose();
			pose.pushMatrix();
			pose.rotateAbout(angle, cx + size / 2.0F, cy + size / 2.0F);
			c.icon(Icon.CHEVRON_DOWN, cx, cy, size, chevronInk);
			pose.popMatrix();
		} else {
			c.icon(Icon.CHEVRON_DOWN, cx, cy, size, chevronInk);
		}
		if (zoomed) {
			c.popOffset();
		}
	}
}
