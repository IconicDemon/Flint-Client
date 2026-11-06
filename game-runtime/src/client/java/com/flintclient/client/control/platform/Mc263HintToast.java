package com.flintclient.client.control.platform;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/**
 * The first-world hint, once ever, in Flint's quiet style: one slim line on a near-black panel with a fine edge, Flint's
 * small mark, "Press [key] to open Flint" with the key on a keycap. About six seconds, sliding in and out as toasts do.
 */
public final class Mc263HintToast implements Toast {
	private static final long SHOW_MILLIS = 6000;
	private static final float HEIGHT = 20;
	private static final float PAD = 6;

	private final TextLine press = new TextLine(FlintFonts.SMALL, "Press");
	private final TextLine key;
	private final TextLine rest = new TextLine(FlintFonts.SMALL, "to open Flint");
	private Visibility wanted = Visibility.SHOW;
	private int width = 100;
	/** Its height in GUI units, as last drawn (the toast is drawn a size smaller than the game's interface). */
	private int height = 14;

	public Mc263HintToast(String keyName) {
		this.key = new TextLine(FlintFonts.SMALL_BOLD, keyName.toUpperCase(java.util.Locale.ROOT));
	}

	@Override
	public Visibility getWantedVisibility() {
		return wanted;
	}

	@Override
	public void update(ToastManager manager, long visibleMillis) {
		// Once a screen opens (Flint's menu, say) the hint has done its job: it slides away rather than sit over the menu.
		boolean screen = net.minecraft.client.Minecraft.getInstance().gui.screen() != null;
		wanted = screen || wanted == Visibility.HIDE || visibleMillis >= SHOW_MILLIS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE
			: Visibility.SHOW;
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return height;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long visibleMillis) {
		if (!Mc263NoticeToast.GUARD.on()) {
			wanted = Visibility.HIDE;
			return;
		}
		try {
			draw(graphics, font, visibleMillis);
		} catch (RuntimeException | LinkageError e) {
			wanted = Visibility.HIDE;
			Mc263NoticeToast.GUARD.fail(e);
		}
	}

	private void draw(GuiGraphicsExtractor graphics, Font font, long visibleMillis) {
		MenuTheme.Look look = ControlState.settings().menuTheme().look();
		Canvas c = Canvas.begin(Frame.of(graphics));
		// In Minecraft's font at the game's own interface scale, as Minecraft's own toasts.
		// One step smaller than the interface (2x text at GUI scale 3): a light hint, not a banner.
		int textScale = c.guiScale() >= 3 ? c.guiScale() - 1 : c.guiScale();
		float k = textScale / (float) c.guiScale();
		int fontMode = com.flintclient.client.render.PixelFace.begin(textScale);
		try {
			int markH = c.px(10 * k);
			int markW = com.flintclient.client.render.MaskAtlas.markWidth(markH);
			int gap = c.px(4 * k);
			int keyPad = c.px(3.5F * k);
			int keyW = c.width(key) + 2 * keyPad;
			int contentW = markW + c.px(6 * k) + c.width(press) + gap + keyW + gap + c.width(rest);
			int w = contentW + 2 * c.px((PAD + 1) * k);
			// The toast is as wide as its line (in GUI units, rounded up), right-aligned as toasts are.
			width = (int) Math.ceil(w / (float) c.scale());
			height = (int) Math.ceil(HEIGHT * k);
			int h = c.px(HEIGHT * k);
			int x0 = c.px(width) - w;
			int ink = 0xFF000000 | look.ink;
			int muted = Palette.lerp(0xFF000000 | look.surface1, ink, 0.62F);
			c.panel(x0, 0, w, h, c.px(5 * k), Math.max(1, c.px(0.4F)), 0xFF000000 | look.surface4, 0xF2000000 | look.surface1);
			int cy = h / 2;
			int x = x0 + c.px((PAD + 1) * k);
			c.mark(x, cy - markH / 2, markH, 1.0F);
			x += markW + c.px(6 * k);
			c.text(press, x, c.baselineFor(press, cy), muted);
			x += c.width(press) + gap;
			int keyH = c.px(12 * k);
			c.panel(x, cy - keyH / 2, keyW, keyH, c.px(2.5F * k), Math.max(1, c.px(0.4F)), Palette.lerp(0xFF000000 | look.surface4, ink, 0.18F),
				0xFF000000 | look.surface3);
			c.text(key, x + keyPad, c.baselineFor(key, cy), ink);
			x += keyW + gap;
			c.text(rest, x, c.baselineFor(rest, cy), muted);
		} finally {
			com.flintclient.client.render.PixelFace.end(fontMode);
			c.end();
		}
	}
}
