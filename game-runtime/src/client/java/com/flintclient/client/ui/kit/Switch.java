package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import org.jspecify.annotations.Nullable;

/**
 * An on/off switch: a 20×11 track (fully rounded) with a 9-unit knob, right-aligned and vertically centred in
 * its bounds (which may be larger, as a hit target). Off: a dark track and a muted knob on the left; on: an ember
 * track and a dark knob on the right, so the state is a position as well as a colour. The knob slides in about
 * 120 ms. The narrator says "on" or "off". Rows that toggle on Space themselves can take it out of the tab order
 * ({@link #tabStop(boolean)}).
 */
public class Switch extends Node {
	/** Told the new state after the user flips it. */
	@FunctionalInterface
	public interface Listener {
		void changed(boolean on);
	}

	public static final float TRACK_W = 20;
	public static final float TRACK_H = 11;
	private static final float KNOB = 9;
	/** A time constant that settles the knob in about 120 ms. */
	private static final float SLIDE_TAU = 0.035F;

	private final @Nullable Listener listener;
	private boolean on;
	private boolean tabStop = true;
	/** The knob's eased position, 0 (off) to 1 (on). */
	private float knob;

	public Switch(boolean on, @Nullable Listener listener) {
		this.on = on;
		this.knob = on ? 1 : 0;
		this.listener = listener;
		this.w = TRACK_W;
		this.h = TRACK_H;
	}

	public boolean on() {
		return on;
	}

	/** Sets the state without telling the listener (the model changed elsewhere). */
	public void setOn(boolean on) {
		this.on = on;
	}

	/** Whether Tab stops on the switch itself (a row that is its own tab stop takes it out). */
	public Switch tabStop(boolean stop) {
		this.tabStop = stop;
		return this;
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public boolean focusable() {
		return tabStop;
	}

	@Override
	public float prefWidth() {
		return TRACK_W;
	}

	@Override
	public float prefHeight(float width) {
		return TRACK_H;
	}

	@Override
	public String narration() {
		String name = accessibleName();
		return withReason((name.isEmpty() ? "Switch" : name) + ", " + (on ? "on" : "off"));
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		return button == Kit.LEFT_BUTTON;
	}

	@Override
	public void mouseUp(float mx, float my, int button) {
		if (button == Kit.LEFT_BUTTON && enabled() && containsPoint(mx, my)) {
			activate();
		}
	}

	@Override
	public void activate() {
		if (!enabled()) {
			return;
		}
		on = !on;
		if (screen != null) {
			screen.playClick();
			if (focused()) {
				// Say the new state; the narrator otherwise only speaks on focus changes.
				screen.announce(this);
			}
		}
		if (listener != null) {
			listener.changed(on);
		}
	}

	/** The track's left edge in units (right-aligned in the bounds). */
	protected float trackX() {
		return x + w - TRACK_W;
	}

	protected float trackY() {
		return y + (h - TRACK_H) / 2;
	}

	@Override
	protected void paint(Canvas c) {
		knob = Kit.approach(knob, on ? 1 : 0, SLIDE_TAU);
		paintSwitch(c, trackX(), trackY(), knob, hover, enabled());
		Kit.focusRing(c, trackX(), trackY(), TRACK_W, TRACK_H, TRACK_H / 2, focus);
	}

	/** Draws a switch at ({@code x}, {@code y}) units with its knob {@code knob} of the way to on (0..1). */
	public static void paintSwitch(Canvas c, float x, float y, float knob, float hover, boolean enabled) {
		int tx = c.px(x);
		int ty = c.px(y);
		int tw = c.px(x + TRACK_W) - tx;
		int th = c.px(y + TRACK_H) - ty;
		int offTrack = Palette.lerp(Tokens2.SWITCH_OFF_TRACK, 0xFF34302C, hover);
		int onTrack = Palette.lerp(Tokens2.EMBER, Tokens2.EMBER_HOVER, hover);
		int track = Palette.lerp(offTrack, onTrack, knob);
		int knobColor = Palette.lerp(Palette.lerp(Tokens2.muted(), Tokens2.TEXT_DIM, hover), Tokens2.SWITCH_ON_KNOB, knob);
		float alpha = enabled ? 1 : 0.4F;
		c.roundRect(tx, ty, tw, th, th / 2, Palette.withAlpha(track, alpha));
		if (Kit.prefs().highContrast() && knob < 0.5F) {
			c.ring(tx, ty, tw, th, th / 2, 1, Tokens2.hairlineStrong());
		}
		float inset = (TRACK_H - KNOB) / 2;
		int ks = c.px(KNOB);
		int travel = tw - ks - 2 * c.px(inset);
		int kx = tx + c.px(inset) + Math.round(travel * knob);
		int ky = ty + (th - ks) / 2;
		c.roundRect(kx, ky, ks, ks, ks / 2, Palette.withAlpha(knobColor, alpha));
	}
}
