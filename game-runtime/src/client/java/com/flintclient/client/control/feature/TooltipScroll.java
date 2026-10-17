package com.flintclient.client.control.feature;

/**
 * Scrollable Tooltips: an offset and a scale for the tooltip being shown, so a tooltip longer or wider than the window can
 * be read in full. The version layer reports where Minecraft placed each tooltip ({@link #place}), asks for the offset to
 * draw it at, and passes on wheel notches ({@link #scroll}). Offsets are clamped so a tooltip that doesn't fit can be
 * moved until its other edge shows, and no further; one that fits doesn't move unless free scrolling is on. Pure Java,
 * render thread, allocation-free.
 */
public final class TooltipScroll {
	/** Units moved per wheel notch (one line of tooltip text). */
	public static final float LINE = 10;
	/** Units kept clear of the window's edge, as Minecraft keeps tooltips. */
	static final float MARGIN = 4;
	/** In free scrolling, this much of the tooltip always stays on screen. */
	static final float KEEP = 16;
	public static final float MIN_SCALE = 0.25F;
	public static final float MAX_SCALE = 2.0F;
	/** A tooltip unseen for this long is gone: the next one is new. */
	static final long GONE_NANOS = 150_000_000L;
	/** How long a smooth scroll takes to settle (about 95%). */
	private static final float GLIDE_MS = 60;

	private float targetX;
	private float targetY;
	private float shownX;
	private float shownY;
	private float scale = 1;
	private int identityW = -1;
	private int identityH = -1;
	private int identityLines = -1;
	private long lastSeen = Long.MIN_VALUE;
	private long lastFrame;
	// Where Minecraft put the tooltip this frame (before the offset), its scaled size and the window.
	private float x;
	private float y;
	private float w;
	private float h;
	private float screenW;
	private float screenH;
	private boolean free;

	/**
	 * A tooltip is about to be drawn: its unscaled size and line count identify it. A different tooltip from the last one
	 * (or one after a pause) starts at {@code defaultScale}; with {@code snapToStart} it also starts at its top-left edge.
	 */
	public void begin(int width, int height, int lines, long nowNanos, boolean snapToStart, float defaultScale) {
		boolean fresh = width != identityW || height != identityH || lines != identityLines || nowNanos - lastSeen > GONE_NANOS;
		identityW = width;
		identityH = height;
		identityLines = lines;
		lastSeen = nowNanos;
		if (fresh) {
			scale = clampScale(defaultScale);
			if (snapToStart) {
				targetX = 0;
				targetY = 0;
				shownX = 0;
				shownY = 0;
				pendingSnap = true;
			}
		}
	}

	private boolean pendingSnap;

	/** The scale to draw this tooltip at. */
	public float scale() {
		return scale;
	}

	/**
	 * Where Minecraft placed it ({@code x, y}, for its scaled size {@code w x h}) in a window {@code screenW x screenH}. A
	 * new tooltip that snaps to start is moved so its top (and left) edge shows.
	 */
	public void place(float x, float y, float w, float h, float screenW, float screenH, boolean free, long nowNanos, boolean smooth) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		this.screenW = screenW;
		this.screenH = screenH;
		this.free = free;
		if (pendingSnap) {
			pendingSnap = false;
			targetY = clamp(MARGIN - y, minY(), maxY());
			targetX = clamp(0, minX(), maxX());
			shownX = targetX;
			shownY = targetY;
		}
		targetX = clamp(targetX, minX(), maxX());
		targetY = clamp(targetY, minY(), maxY());
		float dt = lastFrame == 0 ? 0 : (nowNanos - lastFrame) / 1_000_000F;
		lastFrame = nowNanos;
		if (!smooth || dt <= 0 || dt > 250) {
			shownX = targetX;
			shownY = targetY;
		} else {
			float k = 1 - (float) Math.exp(-3 * dt / GLIDE_MS);
			shownX += (targetX - shownX) * k;
			shownY += (targetY - shownY) * k;
			if (Math.abs(targetX - shownX) < 0.05F) {
				shownX = targetX;
			}
			if (Math.abs(targetY - shownY) < 0.05F) {
				shownY = targetY;
			}
		}
	}

	/** The horizontal offset to draw at now (whole units). */
	public int offsetX() {
		return Math.round(shownX);
	}

	public int offsetY() {
		return Math.round(shownY);
	}

	/** Whether a tooltip was drawn within the last moment (so a wheel notch is meant for it). */
	public boolean showing(long nowNanos) {
		return nowNanos - lastSeen <= GONE_NANOS;
	}

	/** Whether the tooltip is taller (vertical) or wider than the window allows. */
	public boolean overflows(boolean vertical) {
		return vertical ? h + 2 * MARGIN > screenH : w + 2 * MARGIN > screenW;
	}

	/**
	 * A wheel movement of {@code amount} notches (positive: up or right) on one axis: moves the tooltip (true) if it can
	 * move on that axis. Moving up reveals what is above, so the tooltip goes down. {@code lineShift} moves whole lines.
	 */
	public boolean scroll(boolean vertical, double amount, boolean lineShift) {
		if (amount == 0 || !free && !overflows(vertical)) {
			return false;
		}
		float step = (float) amount * LINE;
		if (lineShift) {
			step = Math.signum(step) * Math.max(LINE, Math.round(Math.abs(step) / LINE) * LINE);
		}
		if (vertical) {
			targetY = clamp(targetY + step, minY(), maxY());
		} else {
			targetX = clamp(targetX + step, minX(), maxX());
		}
		return true;
	}

	/** A wheel movement while the scale key is held: 10% per notch. */
	public boolean zoom(double amount) {
		if (amount == 0) {
			return false;
		}
		scale = clampScale(scale * (float) Math.pow(1.1, amount));
		return true;
	}

	static float clampScale(float value) {
		return Float.isNaN(value) ? 1 : Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
	}

	private float minY() {
		return free ? -(y + h - KEEP) : Math.min(0, screenH - MARGIN - (y + h));
	}

	private float maxY() {
		return free ? screenH - KEEP - y : Math.max(0, MARGIN - y);
	}

	private float minX() {
		return free ? -(x + w - KEEP) : Math.min(0, screenW - MARGIN - (x + w));
	}

	private float maxX() {
		return free ? screenW - KEEP - x : Math.max(0, MARGIN - x);
	}

	private static float clamp(float value, float min, float max) {
		return min > max ? 0 : Math.max(min, Math.min(max, value));
	}
}
