package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.NoticeStyle;
import com.flintclient.client.render.TextLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/**
 * A short Flint notice ("FPS was turned off · error", "Backed up New World") in Flint's one notification style
 * ({@code render.NoticeStyle}): a slim translucent pill, Flint's mark, a thin orange accent, white text and grey after " · ". It
 * eases in and fades out, always fits the screen (a long name is cut short), and a new notice while one shows takes its place
 * instead of stacking up. Four seconds.
 */
public final class Mc263NoticeToast implements Toast {
	private static final long SHOW_MILLIS = 4000;
	private static final float HEIGHT = NoticeStyle.HEIGHT;
	/** The notice on screen now, which the next one replaces (game thread). */
	private static Mc263NoticeToast live;
	private TextLine text;
	private TextLine secondary;
	/** When the text last changed, in the toast's own time (-1: at the next update). */
	private long since;
	private long shownFor;
	/** Both of Flint's toasts: one that can't draw on this Minecraft hides, and no more are shown this session. */
	static final HookGuard GUARD = new HookGuard("toasts", (path, error) -> FlintClient.LOGGER.warn("Flint: toasts can't draw on this Minecraft; they're off for this session", error));
	private Visibility wanted = Visibility.SHOW;
	private int width = 140;

	private Mc263NoticeToast(String text) {
		setText(text);
	}

	private void setText(String all) {
		int dot = all.indexOf(" · ");
		this.text = new TextLine(FlintFonts.SMALL, dot > 0 ? all.substring(0, dot) : all);
		this.secondary = dot > 0 ? new TextLine(FlintFonts.SMALL, all.substring(dot + 1)) : null;
		since = -1;
	}

	/** Shows {@code text} (render thread or not: it is queued onto the game's thread). */
	public static void show(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			Mc263NoticeToast now = live;
			if (now != null && now.wanted == Visibility.SHOW) {
				// One at a time: the new text takes the shown one's place and its time starts again, never a wall of toasts.
				now.setText(text);
				return;
			}
			live = new Mc263NoticeToast(text);
			mc.gui.toastManager().addToast(live);
		});
	}

	@Override
	public Visibility getWantedVisibility() {
		return wanted;
	}

	@Override
	public void update(ToastManager manager, long visibleMillis) {
		if (since < 0) {
			since = visibleMillis;
		}
		shownFor = visibleMillis - since;
		wanted = visibleMillis - since >= SHOW_MILLIS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return Math.round(HEIGHT);
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
			float alpha = NoticeStyle.alpha(shownFor, SHOW_MILLIS);
			int h = c.px(HEIGHT);
			int chrome = NoticeStyle.chromeWidth(c, h);
			int gap = c.px(3);
			int wanted = chrome + c.width(text) + (secondary != null ? gap + c.width(secondary) : 0);
			int screen = c.px(Minecraft.getInstance().getWindow().getGuiScaledWidth());
			// Never off the screen: at most the screen less a margin either side, the text cut short to fit.
			int w = NoticeStyle.fitWidth(wanted, screen, c.px(NoticeStyle.MARGIN));
			width = Math.max(1, (int) Math.ceil(w / (float) c.scale()) + Math.round(NoticeStyle.MARGIN));
			int x0 = c.px(width - NoticeStyle.MARGIN) - w;
			int x = NoticeStyle.pill(c, x0, 0, w, h, alpha);
			int room = x0 + w - c.px(NoticeStyle.PAD) - x;
			int cy = h / 2;
			int mainW = Math.min(c.width(text), room);
			c.text(text, x, c.baselineFor(text, cy), NoticeStyle.fade(NoticeStyle.TEXT, alpha), room);
			if (secondary != null && room - mainW - gap > c.px(8)) {
				c.text(secondary, x + mainW + gap, c.baselineFor(secondary, cy), NoticeStyle.fade(NoticeStyle.SECONDARY, alpha), room - mainW - gap);
			}
		} finally {
			com.flintclient.client.render.PixelFace.end(fontMode);
			c.end();
		}
	}
}
