package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.Frame;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.MeritIcon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import org.jspecify.annotations.Nullable;

/**
 * Daily Rewards, a window of its own (not part of the Right Shift menu), laid out as a daily streak page: opened from the main menu's Daily Rewards card (and
 * on Minecraft 1.8.9 from its title screen), over Flint's artwork or the game, dimmed and blurred like Flint's other menus,
 * with slow motes of gold drifting up behind it. It fades and rises in over 180 ms and out over 120 ms; Escape or its close
 * button go back to where it was opened from. Same window on every Minecraft line (shared code, the Right Shift menu's look).
 * A fault while building, drawing or handling input closes it and is logged once.
 */
public final class DailyRewardsScreen extends KitScreen {
	private static final long CLOSE_TIMEOUT_MILLIS = 600;
	private static final float RISE = 12;
	private static final float DROP = 6;
	private static final int MOTES = 16;

	private final @Nullable Object parent;
	private final MenuMotion motion = new MenuMotion();
	private @Nullable RewardsWindow window;
	private boolean started;
	private boolean framed;
	private boolean closing;
	private boolean closed;
	private boolean failed;
	private long closingSince;
	private final long openedAt = Clock.millis();

	public DailyRewardsScreen(@Nullable Object parent) {
		super("Daily Rewards", 320, 200);
		this.parent = parent;
	}

	@Override
	protected void build(Node root) {
		try {
			window = root.add(new RewardsWindow(this::closeRequested));
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	@Override
	protected void layoutContent(float width, float height) {
		if (window != null) {
			window.setBounds(0, 0, width, height);
		}
	}

	@Override
	protected int computeUiScale(int windowW, int windowH) {
		return MenuStyle.apply(windowW, windowH);
	}

	@Override
	protected float tooltipScale() {
		return MenuStyle.tooltipScale();
	}

	@Override
	protected boolean minecraftFont() {
		return true;
	}

	@Override
	protected TooltipLook tooltipLook() {
		return MenuStyle.tooltipLook();
	}

	@Override
	protected int minecraftFontScale() {
		return MenuStyle.pixelScale();
	}

	@Override
	protected void initialFocus(boolean keyboard) {
	}

	@Override
	protected void shown() {
		super.shown();
		if (started) {
			return;
		}
		started = true;
		try {
			motion.open();
			if (ControlState.settings().soundsEnabled()) {
				MenuHost.playOpenSound(ControlState.settings().soundVolume());
			}
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	@Override
	protected void update() {
		if (failed || closing && Clock.millis() - closingSince > CLOSE_TIMEOUT_MILLIS) {
			finishClose();
		}
	}

	@Override
	protected void closeRequested() {
		if (closing) {
			return;
		}
		closing = true;
		closingSince = Clock.millis();
		motion.close();
		try {
			if (ControlState.settings().soundsEnabled()) {
				MenuHost.playCloseSound(ControlState.settings().soundVolume());
			}
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	private void finishClose() {
		closing = false;
		closed = false;
		if (hostIsShown()) {
			hostShow(parent);
		}
	}

	/** A fault: logged once, and the window closes (the Right Shift menu and the rest of Flint carry on). */
	private void broken(Throwable error) {
		if (!failed) {
			failed = true;
			com.flintclient.client.FlintLog.LOGGER.warn("Flint: the Daily Rewards window failed and closed", error);
		}
	}

	@Override
	protected boolean acceptsInput() {
		return !failed && motion.opening();
	}

	@Override
	protected void onEscape() {
		closeRequested();
	}

	// ---------- drawing ----------

	@Override
	protected void paintBackground(Frame frame) {
		try {
			super.paintBackground(frame);
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	/** What is behind the window (blurred when that's on), dimmed with the fade, and the gold motes rising slowly through it. */
	@Override
	protected void paintBackdrop(Canvas c) {
		try {
			boolean first = !framed;
			framed = true;
			MenuStyle.applyTheme(ControlState.settings().menuTheme());
			if (motion.advance(first ? 0 : Motion.frameSeconds())) {
				closed = true;
			}
			float shown = motion.shown();
			setOpacity(shown);
			setContentShift(motion.offset(MenuStyle.r(RISE), MenuStyle.r(DROP)));
			setContentZoom(motion.zoom(0.97F, 0.985F));
			if (!hostInWorld()) {
				hostPaintMenuBackdrop(c);
				c.frame().nextLayer();
			}
			if (ControlState.settings().transitionBlur() && MenuHost.menuBlurOn()) {
				c.frame().blurBehind();
			}
			float previous = c.opacity();
			c.setOpacity(previous * shown);
			c.fill(0, 0, c.px(hostGuiWidth()), c.px(hostGuiHeight()), MenuStyle.SCRIM);
			paintMotes(c);
			c.setOpacity(previous);
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	/**
	 * Small Merits drifting up the screen, each on its own slow path (a gentle sway, fading in at the bottom and out near
	 * the top). Their places follow from the time alone, so nothing is kept per mote. None with reduced motion.
	 */
	private void paintMotes(Canvas c) {
		if (Kit.reduced()) {
			return;
		}
		int sw = c.px(hostGuiWidth());
		int sh = c.px(hostGuiHeight());
		long t = Clock.millis() - openedAt;
		float appear = Math.clamp(t / 900.0F, 0, 1);
		for (int i = 0; i < MOTES; i++) {
			// A fixed scatter per mote (golden-ratio steps), its speed and size from the same.
			float seed = (i * 0.6180339887F) % 1;
			float speed = 0.000018F + 0.000022F * ((i * 0.37F) % 1);
			float phase = (seed + t * speed) % 1;
			float px = sw * ((i * 0.7548776662F + 0.05F) % 1) + (float) Math.sin(t / 1700.0 + i) * c.px(d(18));
			float py = sh * (1.08F - 1.16F * phase);
			float alpha = (float) Math.sin(Math.PI * phase) * (0.07F + 0.11F * ((i * 0.53F) % 1)) * appear;
			int size = Math.max(3, c.px(d(5 + 7 * ((i * 0.29F) % 1))));
			MeritIcon.drawMoving(c, Math.round(px), Math.round(py), size, Palette.withAlpha(0xFFFFFFFF, alpha));
		}
	}

	@Override
	protected void paint(Frame frame) {
		if (closed || failed) {
			FlintFrame.end();
			finishClose();
			return;
		}
		try {
			super.paint(frame);
		} catch (RuntimeException | LinkageError e) {
			FlintFrame.end();
			broken(e);
		}
	}

	/**
	 * The window: a back button at its top left, outside it, and a block holding the rewards ({@link RewardsPage}), sized to
	 * the window and kept in proportion as the Right Shift menu is.
	 */
	private static final class RewardsWindow extends Node {
		private static final float WIN_W = 1240;
		private static final float WIN_H = 640;
		private static final float BACK = 44;
		private static final float GAP = 14;
		private static final float EDGE = 24;

		private final Parts.Square back;
		private final RewardsPage page;
		private float winX;
		private float winY;
		private float winW;
		private float winH;

		RewardsWindow(Runnable onClose) {
			back = add(new Parts.Square(Glyph.CHEVRON_LEFT, "Back", onClose));
			page = add(new RewardsPage());
		}

		@Override
		protected void layout() {
			float side = d(BACK) + d(GAP);
			winW = Math.min(w - 2 * d(EDGE) - 2 * side, d(WIN_W));
			winX = x + (w - winW) / 2;
			winH = Math.max(d(420), Math.min(d(WIN_H), h - 2 * d(EDGE)));
			winY = y + (h - winH) / 2;
			back.setBounds(winX - side, winY, d(BACK), d(BACK));
			page.setBounds(winX, winY, winW, winH);
		}

		@Override
		protected void paint(Canvas c) {
			MenuStyle.block(c, winX, winY, winW, winH, MenuStyle.radius(), 0, 0);
		}
	}
}
