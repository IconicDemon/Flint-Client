package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.MenuTheme;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;

/** A short Flint notice ("Backed up New World"), in the hint's quiet style: Flint's small mark and one line, for four seconds. */
public final class Mc263NoticeToast implements Toast {
	private static final long SHOW_MILLIS = 4000;
	private static final float HEIGHT = 20;
	private final TextLine text;
	/** Both of Flint's toasts: one that can't draw on this Minecraft hides, and no more are shown this session. */
	static final HookGuard GUARD = new HookGuard("toasts", (path, error) -> FlintClient.LOGGER.warn("Flint: toasts can't draw on this Minecraft; they're off for this session", error));
	private Visibility wanted = Visibility.SHOW;
	private int width = 140;

	private Mc263NoticeToast(String text) {
		this.text = new TextLine(FlintFonts.SMALL, text);
	}

	/** Shows {@code text} (render thread or not: it is queued onto the game's thread). */
	public static void show(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.toastManager().addToast(new Mc263NoticeToast(text)));
	}

	@Override
	public Visibility getWantedVisibility() {
		return wanted;
	}

	@Override
	public void update(ToastManager manager, long visibleMillis) {
		wanted = visibleMillis >= SHOW_MILLIS * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
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
		MenuTheme.Look look = ControlState.settings().menuTheme().look();
		Canvas c = Canvas.begin(Frame.of(graphics));
		// In Minecraft's font at the game's own interface scale, as Minecraft's own toasts.
		int fontMode = com.flintclient.client.render.PixelFace.begin(c.guiScale());
		try {
			int markH = c.px(10);
			int markW = com.flintclient.client.render.MaskAtlas.markWidth(markH);
			int w = markW + c.px(6) + c.width(text) + 2 * c.px(7);
			width = (int) Math.ceil(w / (float) c.scale());
			int h = c.px(HEIGHT);
			int x0 = c.px(width) - w;
			c.panel(x0, 0, w, h, c.px(5), Math.max(1, c.px(0.4F)), 0xFF000000 | look.surface4, 0xF2000000 | look.surface1);
			int x = x0 + c.px(7);
			c.mark(x, h / 2 - markH / 2, markH, 1.0F);
			c.text(text, x + markW + c.px(6), c.baselineFor(text, h / 2), Palette.lerp(0xFF000000 | look.surface1, 0xFF000000 | look.ink, 0.85F));
		} finally {
			com.flintclient.client.render.PixelFace.end(fontMode);
			c.end();
		}
	}
}
