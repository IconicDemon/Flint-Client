package com.flintclient.client.control.platform;

import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.TextLine;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/**
 * The first-world hint, once ever, in Flint's one notification style ({@code render.NoticeStyle}): the slim translucent pill
 * with Flint's mark and orange accent, the key on a small chip outlined in orange, then "Flint menu". About six seconds, easing
 * in and fading out; it always fits the screen.
 */
public final class Mc263HintToast implements Toast {
	private static final long SHOW_MILLIS = 6000;
	private static final float HEIGHT = com.flintclient.client.render.NoticeStyle.HEIGHT;

	private final TextLine key;
	private final TextLine rest = new TextLine(FlintFonts.SMALL, "Flint menu");
	private long shownFor;
	private Visibility wanted = Visibility.SHOW;
	private int width = 100;
	/** Its height in GUI units, as last drawn (the toast is drawn a size smaller than the game's interface). */
	private int height = 14;

	public Mc263HintToast(String keyName) {
		this.key = new TextLine(FlintFonts.SMALL, keyName.toUpperCase(java.util.Locale.ROOT));
	}

	/** Shows the hint for {@code keyName} now (the QA tour's look at it; the game shows it once, from ControlCenter). */
	public static void show(String keyName) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		mc.execute(() -> mc.gui.toastManager().addToast(new Mc263HintToast(keyName)));
	}

	@Override
	public Visibility getWantedVisibility() {
		return wanted;
	}

	@Override
	public void update(ToastManager manager, long visibleMillis) {
		// Once a screen opens (Flint's menu, say) the hint has done its job: it slides away rather than sit over the menu.
		boolean screen = net.minecraft.client.Minecraft.getInstance().gui.screen() != null;
		shownFor = visibleMillis;
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
		Canvas c = Canvas.begin(Frame.of(graphics));
		// In Minecraft's font at the game's own interface scale, as Minecraft's own toasts.
		int fontMode = com.flintclient.client.render.PixelFace.begin(c.guiScale());
		try {
			float alpha = com.flintclient.client.render.NoticeStyle.alpha(shownFor, SHOW_MILLIS);
			int h = c.px(HEIGHT);
			int chrome = com.flintclient.client.render.NoticeStyle.chromeWidth(c, h);
			int gap = c.px(4);
			int keyW = c.width(key) + 2 * c.px(3);
			int wanted = chrome + keyW + gap + c.width(rest);
			int screen = c.px(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth());
			int w = com.flintclient.client.render.NoticeStyle.fitWidth(wanted, screen, c.px(com.flintclient.client.render.NoticeStyle.MARGIN));
			width = Math.max(1, (int) Math.ceil(w / (float) c.scale()) + Math.round(com.flintclient.client.render.NoticeStyle.MARGIN));
			height = Math.round(HEIGHT);
			int x0 = c.px(width - com.flintclient.client.render.NoticeStyle.MARGIN) - w;
			int x = com.flintclient.client.render.NoticeStyle.pill(c, x0, 0, w, h, alpha);
			int cy = h / 2;
			x += com.flintclient.client.render.NoticeStyle.keyChip(c, key, x, cy, Math.round(h * 0.7F), alpha) + gap;
			int room = x0 + w - c.px(com.flintclient.client.render.NoticeStyle.PAD) - x;
			if (room > c.px(8)) {
				c.text(rest, x, c.baselineFor(rest, cy), com.flintclient.client.render.NoticeStyle.fade(com.flintclient.client.render.NoticeStyle.TEXT, alpha), room);
			}
		} finally {
			com.flintclient.client.render.PixelFace.end(fontMode);
			c.end();
		}
	}
}
