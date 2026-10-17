package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.LabelPosition;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.network.NetworkCore;
import com.flintclient.client.network.NetworkPlatform;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Tokens;
import org.jspecify.annotations.Nullable;

/**
 * Ping: the latency the server reports for this player (Flint Network Core reads what Minecraft already
 * has; nothing is sent), by default exactly "32 ms". The label goes after (the unit only), before ("Ping 32 ms") or
 * nowhere ("32"). On the HUD it is left out while there is no latency to show (singleplayer, Network Core off, or
 * before the server's first report); previews show a dash.
 *
 * <p>The optional signal bars are four bars of rising height: how many are solid says how good the connection is,
 * the rest stay as faint outlines, so the count carries the meaning and not the colour. The optional colour tints
 * the number (and the solid bars) by Minecraft's player-list steps; the number and the bar count say the same, so
 * the colour is never the only signal. Classic uses Minecraft's own chat colours for it.
 */
final class PingView extends ModuleView {
	private static final HudText BEFORE = new HudText("PING", "Ping:", "ping");
	private static final HudText UNIT = new HudText("ms");
	private static final int BARS = 4;
	/** Good, fair, slow, bad: Flint's palette, and Minecraft's green, yellow, gold and red for Classic. */
	private static final int[] TINTS = {Tokens.SUCCESS, 0xFFE3C58F, Tokens.ACCENT_TEXT, Tokens.DANGER};
	private static final int[] CLASSIC_TINTS = {0xFF55FF55, 0xFFFFFF55, 0xFFFFAA00, 0xFFFF5555};

	/** Three digit cells: pings from 40 to 140 ms are common, and the panel shouldn't change size between them. */
	private final HudField value = new HudField(3, HudField.TEXT);
	private boolean hidden;
	private boolean showBars;
	private boolean tint;
	private int band = -1;
	private int filled;
	private int barsX;

	PingView(int surface) {
		super(ModuleId.PING, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		LabelPosition position = state.choice(ModuleSettings.Ping.LABEL_POSITION);
		value.affixes(position == LabelPosition.BEFORE ? BEFORE : null, position == LabelPosition.HIDDEN ? null : UNIT);
		showBars = state.bool(ModuleSettings.Ping.SHOW_BARS);
		tint = state.bool(ModuleSettings.Ping.COLOR_BY_LATENCY);
		int latency = mode == PLACEHOLDER ? NetworkPlatform.UNKNOWN : latency();
		hidden = mode == HUD && latency == NetworkPlatform.UNKNOWN;
		if (latency == NetworkPlatform.UNKNOWN) {
			value.clear();
			filled = 0;
			band = -1;
		} else {
			value.set(latency);
			filled = HudFormat.signalBars(latency);
			band = HudFormat.latencyBand(latency);
		}
	}

	/** The server-reported latency, or {@link NetworkPlatform#UNKNOWN} (singleplayer, Network Core off, no report yet). */
	private static int latency() {
		return !ControlState.latencyKnown() || !ControlState.multiplayer() ? NetworkPlatform.UNKNOWN : NetworkCore.stats().latencyMillis();
	}

	@Override
	String emptyReason() {
		String reason = descriptor.availability(ControlState.inWorld(), ControlState.multiplayer(), ControlState.latencyKnown(),
			ControlState.networkCoreRunning()).reason();
		return reason != null ? reason : "Waiting for the server to report it";
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Set by the Label setting of this module";
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		if (hidden) {
			width = 0;
			height = 0;
			return;
		}
		value.color(tintColor(p.s));
		int end = value.run(p, false, 0, 0);
		barsX = end + p.s.space;
		width = showBars ? barsX + barsWidth(p) : end;
		height = p.s.lineH;
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		int baseline = p.baseline(y);
		value.color(tintColor(p.s));
		value.run(p, true, x, baseline);
		if (showBars) {
			drawBars(p, x + barsX, baseline);
		}
	}

	/** The latency tint in this style, or 0 for none. */
	private int tintColor(HudStyle s) {
		if (!tint || band < 0) {
			return 0;
		}
		return (s.classic() ? CLASSIC_TINTS : TINTS)[band];
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		int latency = latency();
		return latency == NetworkPlatform.UNKNOWN ? -1 : HudFormat.pingSummary(latency, out);
	}

	private static int barWidth(HudPen p) {
		return Math.max(1, p.s.px(1.5F));
	}

	private static int barGap(HudPen p) {
		return Math.max(1, p.s.px(1));
	}

	private static int barsWidth(HudPen p) {
		return BARS * barWidth(p) + (BARS - 1) * barGap(p);
	}

	/**
	 * Bars standing on the baseline, from a quarter of the capital height to all of it. An empty bar keeps its
	 * shape as a faint outline; when a bar is too narrow for an outline to leave a hole (two outline widths or
	 * less, as at GUI scale 1), it shrinks to a faint stub on the baseline instead, so empty never looks like a
	 * dimmer full bar: the count of full-height bars is the signal, by shape and not only by colour.
	 */
	private void drawBars(HudPen p, int x, int baseline) {
		int bw = barWidth(p);
		int gap = barGap(p);
		int tallest = p.cap(HudFaces.VALUE);
		int line = Math.max(1, p.s.unitPx / 3);
		int solid = tintColor(p.s) != 0 ? tintColor(p.s) : p.s.text;
		int faint = Palette.withAlpha(p.s.text, (p.s.text >>> 24) / 255.0F * 0.35F);
		for (int i = 0; i < BARS; i++) {
			int h = Math.max(2, Math.round(tallest * (i + 1) / (float) BARS));
			int left = x + i * (bw + gap);
			if (i < filled) {
				p.fill(left, baseline - h, left + bw, baseline, solid);
			} else if (bw > 2 * line) {
				p.ring(left, baseline - h, bw, h, 0, line, faint);
			} else {
				p.fill(left, baseline - line, left + bw, baseline, faint);
			}
		}
	}
}
