package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.platform.Clock;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.ui.FlintFrame;
import com.flintclient.client.ui.Motion;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import org.jspecify.annotations.Nullable;

/**
 * A window of Flint's own over the main menu or the game, as Daily Rewards is ({@link DailyRewardsScreen}): the Right Shift
 * menu's look and Minecraft's font, a back button at the window's top left (outside it), the content in one block sized to
 * the screen. It fades and rises in over 180 ms and out over 120 ms; Escape or the back button go back to where it was
 * opened from. A fault while building, drawing or handling input closes it and is logged once. Same window on every
 * Minecraft line. Subclasses give the content ({@link #content}) and may tick ({@link #step}).
 */
abstract class WindowScreen extends KitScreen {
	private static final long CLOSE_TIMEOUT_MILLIS = 600;
	private static final float RISE = 12;
	private static final float DROP = 6;

	private final @Nullable Object parent;
	private final MenuMotion motion = new MenuMotion();
	private final float maxW;
	private final float maxH;
	private @Nullable Window window;
	private boolean started;
	private boolean framed;
	private boolean closing;
	private boolean closed;
	private boolean failed;
	private long closingSince;

	/** {@code maxW × maxH}: the block's largest size in design pixels. */
	WindowScreen(String title, @Nullable Object parent, float maxW, float maxH) {
		super(title, 320, 200);
		this.parent = parent;
		this.maxW = maxW;
		this.maxH = maxH;
	}

	/** The window's content, placed in its block. */
	protected abstract Node content();

	/** Once per game tick while shown. */
	protected void step() {
	}

	/** The screen this window goes back to. */
	protected final @Nullable Object parent() {
		return parent;
	}

	@Override
	protected void build(Node root) {
		try {
			window = root.add(new Window(content(), this::closeRequested, maxW, maxH));
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
			return;
		}
		try {
			step();
		} catch (RuntimeException | LinkageError e) {
			broken(e);
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

	/** A fault: logged once, and the window closes (the rest of Flint carries on). */
	protected final void broken(Throwable error) {
		if (!failed) {
			failed = true;
			com.flintclient.client.FlintLog.LOGGER.warn("Flint: the {} window failed and closed", getClass().getSimpleName(), error);
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

	@Override
	protected void paintBackground(com.flintclient.client.platform.Frame frame) {
		try {
			super.paintBackground(frame);
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	/** What is behind the window (blurred when that's on), dimmed with the fade. */
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
			c.setOpacity(previous);
		} catch (RuntimeException | LinkageError e) {
			broken(e);
		}
	}

	@Override
	protected void paint(com.flintclient.client.platform.Frame frame) {
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

	/** Shows {@code url} after Minecraft's link confirmation, which comes back to this window. */
	protected final void openLink(String url) {
		try {
			MenuHost.openLink(this, url);
		} catch (RuntimeException | LinkageError e) {
			com.flintclient.client.FlintLog.LOGGER.warn("Flint: couldn't open {}", url, e);
		}
	}

	/** The window: a back button at its top left, outside it, and the content's block, kept in proportion. */
	private static final class Window extends Node {
		private static final float BACK = 44;
		private static final float GAP = 14;
		private static final float EDGE = 24;

		private final Parts.Square back;
		private final Node page;
		private final float maxW;
		private final float maxH;
		private float winX;
		private float winY;
		private float winW;
		private float winH;

		Window(Node page, Runnable onClose, float maxW, float maxH) {
			this.maxW = maxW;
			this.maxH = maxH;
			back = add(new Parts.Square(Glyph.CHEVRON_LEFT, "Back", onClose));
			this.page = add(page);
		}

		@Override
		protected void layout() {
			float side = d(BACK) + d(GAP);
			winW = Math.min(w - 2 * d(EDGE) - 2 * side, d(maxW));
			winX = x + (w - winW) / 2;
			winH = Math.max(Math.min(d(460), h - 2 * d(EDGE)), Math.min(d(maxH), h - 2 * d(EDGE)));
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
