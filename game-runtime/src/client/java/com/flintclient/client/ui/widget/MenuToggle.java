package com.flintclient.client.ui.widget;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.MaskAtlas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.flintclient.client.ui.Motion;
import java.util.function.Consumer;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * The choice of main menu, in the top-left corner of Flint's menu and of Minecraft's title screen alike: a glass pill with
 * both menus side by side (Flint's mark and "Flint", the grass block and "Vanilla") and a knob under the one shown. Picking
 * the other slides the knob across (stretching a little on the way, its glow turning from ember to grass) while the menus
 * blend into each other. The knob's travel is kept here, not per switch, so the switch on the menu that takes over carries
 * on exactly where the one on the menu that left was.
 */
public final class MenuToggle extends FlintButton {
	/** The height in menu units: the top bar's (the account pill and the icon buttons beside it). */
	public static final float HEIGHT = 13.0F;
	/** Between the track's edge and the knob. */
	private static final float INSET = 1.5F;
	/** Inside each half: the room either side, the icon and the gap before the name. */
	private static final float SIDE = 5.0F;
	private static final float ICON = 6.6F;
	private static final float GAP = 3.0F;
	/** How long the knob takes to cross. */
	private static final long SLIDE_MILLIS = 420;

	private static final Identifier GRASS = Identifier.withDefaultNamespace("textures/block/grass_block_side.png");
	/** The knob's glow and edge: Flint's ember on Flint's side, a grass green on Minecraft's. */
	private static final int EMBER = 0xFFE07A1F;
	private static final int GRASS_GREEN = 0xFF6FB443;

	/** The knob's travel, 0 (Flint) to 1 (Vanilla): from where to where, since when. Shared by every switch. */
	private static float from;
	private static float to;
	private static long slideAt;

	private final TextLine flint;
	private final TextLine vanilla;
	private final boolean vanillaShown;
	private final Consumer<Boolean> choose;
	/** The half under the pointer, eased (0 none, 1 lit), for each side. */
	private float hoverFlint;
	private float hoverVanilla;

	/**
	 * A switch on the menu showing Flint's ({@code vanillaShown} false) or Minecraft's; {@code choose} gets the menu picked
	 * (true: Vanilla) when the other half is clicked, or either is chosen from the keyboard.
	 */
	public MenuToggle(Component message, Component flintLabel, Component vanillaLabel, boolean vanillaShown, Consumer<Boolean> choose) {
		super(message, () -> { });
		this.flint = new TextLine(FlintFonts.STRONG, flintLabel.getString());
		this.vanilla = new TextLine(FlintFonts.STRONG, vanillaLabel.getString());
		this.vanillaShown = vanillaShown;
		this.choose = choose;
		if (!sliding(Util.getMillis())) {
			from = to = vanillaShown ? 1.0F : 0.0F;
		}
	}

	/** Starts the knob's slide to {@code vanilla}'s side from wherever it is now. */
	public static void slideTo(boolean vanilla) {
		long now = Util.getMillis();
		from = knob(now);
		to = vanilla ? 1.0F : 0.0F;
		slideAt = Motion.reduced() ? 0 : now;
	}

	private static boolean sliding(long now) {
		return slideAt != 0 && now - slideAt < SLIDE_MILLIS;
	}

	/** Where the knob is (0 Flint .. 1 Vanilla), eased in and out along its way. */
	private static float knob(long now) {
		if (!sliding(now)) {
			return to;
		}
		float t = (now - slideAt) / (float) SLIDE_MILLIS;
		float eased = t < 0.5F ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
		return from + (to - from) * eased;
	}

	/** The width in units that fits both halves. */
	public int preferredWidth(Canvas c) {
		return Math.round(2 * INSET + half(c, flint) + half(c, vanilla) + 0.6F);
	}

	private static float half(Canvas c, TextLine label) {
		return 2 * SIDE + ICON + GAP + c.width(label) / c.unitScale();
	}

	/** A click: where it lands decides the half (the pointer may have moved since the last frame). */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		pointAt(event.x(), event.y());
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void activate() {
		super.activate();
		// The half the pointer is on, or (from the keyboard, the pointer elsewhere) the other one.
		boolean onIt = pointerX >= getX() && pointerX < getX() + getWidth() && pointerY >= getY() && pointerY < getY() + getHeight();
		boolean pickVanilla = onIt ? pointerX >= split() : !vanillaShown;
		if (pickVanilla != vanillaShown) {
			choose.accept(pickVanilla);
		}
	}

	/** Where Flint's half ends, in units (as the halves were last drawn). */
	private float split() {
		return Float.isNaN(splitUnits) ? getX() + getWidth() / 2.0F : splitUnits;
	}

	private float splitUnits = Float.NaN;

	@Override
	protected void paint(Canvas c) {
		long now = Util.getMillis();
		float k = knob(now);
		int x = c.px(getX());
		int y = c.px(getY());
		int w = c.px(getWidth());
		int h = c.px(getHeight());
		float unit = c.unitScale();
		int inset = c.px(INSET);
		int flintW = Math.round(half(c, flint) * unit);
		int vanillaW = w - 2 * inset - flintW;
		splitUnits = getX() + INSET + flintW / unit;

		boolean hovered = isHovered() && active;
		boolean overVanilla = hovered && pointerX >= splitUnits;
		hoverFlint = Motion.toward(hoverFlint, hovered && !overVanilla ? 1.0F : 0.0F, HOVER_MILLIS);
		hoverVanilla = Motion.toward(hoverVanilla, overVanilla ? 1.0F : 0.0F, HOVER_MILLIS);

		boolean zoomed = press > 0.01F;
		if (zoomed) {
			c.pushZoom(1.0F - 0.035F * press, x + w / 2.0F, y + h / 2.0F);
		}
		int r = h / 2;
		// A soft shadow under the pill (it sits on Minecraft's bright panorama as well as on Flint's dark artwork).
		for (int i = 3; i >= 1; i--) {
			int spread = c.px(0.9F * i);
			c.roundRect(x - spread, y - spread + c.px(1.2F), w + 2 * spread, h + 2 * spread, r + spread, 0x16000000);
		}
		// The track: dark glass, lit from above, its edge brighter at the top.
		float lift = Math.max(hover, focus);
		c.roundLinear(x, y, w, h, r, 180.0F, new int[] {Palette.lerp(0xE01A1C22, 0xEA22252C, lift), Palette.lerp(0xE60C0D11, 0xEE111318, lift)},
			Math.max(1, w));
		int edge = Math.max(1, Math.round(c.scale() / 4.0F));
		c.ringLinear(x, y, w, h, r, edge, 180.0F, new int[] {Palette.lerp(0x2EFFFFFF, 0x42FFFFFF, lift), 0x10FFFFFF}, 8);

		// The knob: under the shown half, stretching a little as it crosses; its glow turns from ember to grass on the way.
		int kx0 = x + inset;
		int kx1 = x + inset + flintW;
		float stretch = (float) Math.sin(Math.PI * k) * 0.22F * Math.min(flintW, vanillaW);
		float kw = flintW + (vanillaW - flintW) * k + stretch;
		float kx = kx0 + (kx1 - kx0) * k - stretch / 2.0F;
		kx = Math.max(x + inset, Math.min(kx, x + w - inset - kw));
		int knobX = Math.round(kx);
		int knobW = Math.round(kw);
		int knobY = y + inset;
		int knobH = h - 2 * inset;
		int kr = knobH / 2;
		int tintColor = Palette.lerp(EMBER, GRASS_GREEN, k);
		// Its glow: a few soft layers in its colour.
		for (int i = 3; i >= 1; i--) {
			int spread = c.px(0.7F * i);
			c.roundRect(knobX - spread, knobY - spread, knobW + 2 * spread, knobH + 2 * spread, kr + spread, Palette.withAlpha(tintColor, 0.05F));
		}
		int top = Palette.lerp(0xFF2E2620, 0xFF25301F, k);
		int bottom = Palette.lerp(0xFF1C1713, 0xFF161D13, k);
		c.roundLinear(knobX, knobY, knobW, knobH, kr, 180.0F, new int[] {top, bottom}, Math.max(1, knobW));
		c.ringLinear(knobX, knobY, knobW, knobH, kr, edge, 180.0F,
			new int[] {Palette.withAlpha(tintColor, 0.85F), Palette.withAlpha(tintColor, 0.28F)}, 8);
		// A hint of light along its top.
		int sheen = Math.max(1, Math.round(c.scale() / 4.0F));
		c.roundRect(knobX + kr, knobY + edge, Math.max(0, knobW - 2 * kr), sheen, sheen / 2, 0x1FFFFFFF);

		// The halves: icon then name, the one under the knob bright, the other dim (lit a little under the pointer).
		int icon = c.px(ICON);
		int cy = y + h / 2;
		float flintOn = 1.0F - k;
		float vanillaOn = k;
		int flintInk = Palette.lerp(Palette.lerp(Palette.MUTED, Palette.TEXT_DIM, hoverFlint), Palette.TEXT, flintOn);
		int vanillaInk = Palette.lerp(Palette.lerp(Palette.MUTED, Palette.TEXT_DIM, hoverVanilla), Palette.TEXT, vanillaOn);
		// Flint's mark, centred in its icon's room.
		int fx = x + inset + c.px(SIDE);
		int markH = Math.round(icon * 1.12F);
		c.mark(fx + (icon - MaskAtlas.markWidth(markH)) / 2, cy - markH / 2, markH, 0.55F + 0.45F * Math.max(flintOn, hoverFlint));
		c.text(flint, fx + icon + c.px(GAP), c.baselineFor(flint, cy), flintInk);

		int vx = x + inset + flintW + c.px(SIDE);
		// The grass block at a whole multiple of its 16 pixels' half, so its pixels stay even.
		int grass = Math.max(8, Math.round(icon / 8.0F) * 8);
		int gy = cy - grass / 2;
		int alpha = Math.round(255 * (0.6F + 0.4F * Math.max(vanillaOn, hoverVanilla)));
		c.frame().graphics().blit(RenderPipelines.GUI_TEXTURED, GRASS, vx + (icon - grass) / 2, gy, 0, 0, grass, grass, 16, 16, 16, 16,
			c.tint(alpha << 24 | 0xFFFFFF));
		c.text(vanilla, vx + icon + c.px(GAP), c.baselineFor(vanilla, cy), vanillaInk);

		if (focus > 0.01F) {
			c.ring(x - edge, y - edge, w + 2 * edge, h + 2 * edge, r + edge, edge * 2, Palette.withAlpha(Palette.ACCENT, focus * 0.85F));
		}
		if (zoomed) {
			c.popOffset();
		}
	}
}
