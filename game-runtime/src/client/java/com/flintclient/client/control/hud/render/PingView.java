package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.TextOut;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.LabelPosition;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Tokens;
import org.jspecify.annotations.Nullable;

/**
 * Ping: the latency the server reports for this player (Flint Network Core reads what Minecraft already
 * has; nothing is sent), by default exactly "32 ms", in the boxed-counter look FPS has (in a box, between brackets or
 * just text). The label goes after (the unit only), before ("Ping 32 ms") or nowhere ("32"). On the HUD it is left
 * out while there is no latency to show (singleplayer, Network Core off, or before the server's first report);
 * previews show a dash.
 *
 * <p>The optional signal bars are four bars of rising height after the number: how many are solid says how good the
 * connection is, the rest stay as faint outlines, so the count carries the meaning and not the colour. The optional
 * colour tints the number (and the solid bars) by Minecraft's player-list steps; the number and the bar count say the
 * same, so the colour is never the only signal.
 */
final class PingView extends TextModuleView {
	private static final int BARS = 4;
	/** Good, fair, slow, bad. */
	private static final int[] TINTS = {Tokens.SUCCESS, 0xFFE3C58F, Tokens.ACCENT_TEXT, Tokens.DANGER};

	private final TextOut o = new TextOut();
	private boolean showBars;
	private int filled;

	PingView(int surface) {
		super(ModuleId.PING, surface, ModuleSettings.Ping.STYLE);
	}

	@Override
	int compose(ModuleState state, int mode, long nowNanos, char[] out) {
		int latency = mode == SAMPLE ? 32 : latency();
		showBars = state.bool(ModuleSettings.Ping.SHOW_BARS);
		if (latency == HudData.UNKNOWN_LATENCY) {
			filled = 0;
			return -1;
		}
		filled = HudFormat.signalBars(latency);
		if (state.bool(ModuleSettings.Ping.COLOR_BY_LATENCY)) {
			colors[0] = TINTS[HudFormat.latencyBand(latency)];
		}
		LabelPosition position = state.choice(ModuleSettings.Ping.LABEL_POSITION);
		o.start(out);
		if (position == LabelPosition.BEFORE) {
			o.text("Ping ");
		}
		o.integer(latency);
		if (position != LabelPosition.HIDDEN) {
			o.text(" ms");
		}
		return o.length();
	}

	/** The server-reported latency, or {@link NetworkPlatform#UNKNOWN} (singleplayer, Network Core off, no report yet). */
	private static int latency() {
		return !HudData.latencyKnown() || !HudData.multiplayer() ? HudData.UNKNOWN_LATENCY : HudData.latencyMillis();
	}

	@Override
	String emptyReason() {
		String reason = descriptor.availability(HudData.inWorld(), HudData.multiplayer(), HudData.latencyKnown(),
			HudData.networkCoreRunning()).reason();
		return reason != null ? reason : "Waiting for the server to report it";
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Set by the Label setting of this module";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		int latency = latency();
		return latency == HudData.UNKNOWN_LATENCY ? -1 : HudFormat.pingSummary(latency, out);
	}

	@Override
	int trailWidth(HudPen p) {
		return showBars ? BARS * barWidth(p) + (BARS - 1) * barGap(p) : 0;
	}

	private static int barWidth(HudPen p) {
		return Math.max(1, p.s.px(1.5F));
	}

	private static int barGap(HudPen p) {
		return Math.max(1, p.s.px(1));
	}

	/**
	 * Bars standing on the baseline, from a quarter of the capital height to all of it. An empty bar keeps its
	 * shape as a faint outline; when a bar is too narrow for an outline to leave a hole (two outline widths or
	 * less, as at GUI scale 1), it shrinks to a faint stub on the baseline instead, so empty never looks like a
	 * dimmer full bar: the count of full-height bars is the signal, by shape and not only by colour.
	 */
	@Override
	void drawTrail(HudPen p, int x, int baseline, int color) {
		int bw = barWidth(p);
		int gap = barGap(p);
		int tallest = p.cap(HudFaces.VALUE);
		int line = Math.max(1, p.s.unitPx / 3);
		int faint = Palette.withAlpha(color, (color >>> 24) / 255.0F * 0.35F);
		for (int i = 0; i < BARS; i++) {
			int h = Math.max(2, Math.round(tallest * (i + 1) / (float) BARS));
			int left = x + i * (bw + gap);
			if (i < filled) {
				p.fill(left, baseline - h, left + bw, baseline, color);
			} else if (bw > 2 * line) {
				p.ring(left, baseline - h, bw, h, 0, line, faint);
			} else {
				p.fill(left, baseline - line, left + bw, baseline, faint);
			}
		}
	}
}
