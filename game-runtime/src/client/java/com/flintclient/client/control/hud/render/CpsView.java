package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.hud.CpsCounter;
import com.flintclient.client.control.model.CpsButtons;
import com.flintclient.client.control.model.CpsFormat;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.LabelPosition;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import org.jspecify.annotations.Nullable;

/**
 * CPS: clicks in the last second, by default both buttons as "8 · 3 CPS" (left, then right). One button, or both
 * added together ("Combined"), shows a single count. Both can also read "8 | 3" or "L 8  R 3". The label goes after,
 * before ("CPS 8 · 3", Classic's "CPS: 8 · 3") or nowhere. Only clicks that reached the game are counted (see
 * {@code ControlCenter.onClick}), so a preview on the title screen truthfully shows 0.
 *
 * <p>The numbers are laid out at their own width, so the separator always sits one space from each; the line as a
 * whole keeps the width of two-digit counts, so clicking past ten a second doesn't resize the panel.
 */
final class CpsView extends ModuleView {
	private static final HudText BEFORE = new HudText("CPS", "CPS:", "cps");
	private static final HudText AFTER = new HudText("CPS", "CPS", "cps");
	private static final HudText BAR = new HudText("|");
	private static final HudText L = new HudText("L");
	private static final HudText R = new HudText("R");
	private static final int CELLS = 2;

	private final HudField left = new HudField(CELLS, HudField.TEXT);
	private final HudField right = new HudField(CELLS, HudField.TEXT);
	private final HudField single = new HudField(CELLS, HudField.TEXT);
	private CpsButtons buttons = CpsButtons.BOTH;
	private CpsFormat format = CpsFormat.DOT;
	private LabelPosition position = LabelPosition.AFTER;

	CpsView(int surface) {
		super(ModuleId.CPS, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		buttons = state.choice(ModuleSettings.Cps.BUTTONS);
		format = state.choice(ModuleSettings.Cps.FORMAT);
		position = state.choice(ModuleSettings.Cps.LABEL_POSITION);
		if (mode == PLACEHOLDER) {
			left.clear();
			right.clear();
			single.clear();
			return;
		}
		long now = ControlCenter.nowMillis();
		CpsCounter cps = ControlCenter.cps();
		int l = cps.countLastSecond(CpsCounter.LEFT, now);
		int r = cps.countLastSecond(CpsCounter.RIGHT, now);
		left.set(l);
		right.set(r);
		single.set(switch (buttons) {
			case LEFT, BOTH -> l;
			case RIGHT -> r;
			case COMBINED -> l + r;
		});
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		width = Math.max(run(p, false, 0, 0, false), run(p, false, 0, 0, true));
		height = p.s.lineH;
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		run(p, true, x, p.baseline(y), false);
	}

	/** The line from {@code x}; {@code slots} gives each number its full cells (the line's steady width). */
	private int run(HudPen p, boolean draw, int x, int baseline, boolean slots) {
		HudStyle s = p.s;
		int at = x;
		if (position == LabelPosition.BEFORE) {
			at = label(p, draw, BEFORE, at, baseline) + s.space;
		}
		if (buttons != CpsButtons.BOTH) {
			at = single.run(p, draw, at, baseline, !slots);
		} else if (format == CpsFormat.LETTERS) {
			at = label(p, draw, L, at, baseline) + s.thin;
			at = left.run(p, draw, at, baseline, !slots) + 2 * s.space;
			at = label(p, draw, R, at, baseline) + s.thin;
			at = right.run(p, draw, at, baseline, !slots);
		} else {
			// The dot has room around it in the font already; the bar is a hairline and needs a full space each side.
			int gap = format == CpsFormat.BAR ? s.space : s.thin;
			at = left.run(p, draw, at, baseline, !slots) + gap;
			HudText separator = format == CpsFormat.BAR ? BAR : DOT;
			at = draw ? p.text(separator, HudFaces.VALUE, at, baseline, s.muted) : at + p.width(separator, HudFaces.VALUE);
			at = right.run(p, draw, at + gap, baseline, !slots);
		}
		if (position == LabelPosition.AFTER) {
			at = label(p, draw, AFTER, at + s.thin, baseline);
		}
		return at - x;
	}

	private static int label(HudPen p, boolean draw, HudText text, int x, int baseline) {
		return draw ? p.text(text, HudFaces.LABEL, x, baseline, p.s.label) : x + p.width(text, HudFaces.LABEL);
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Set by the Label setting of this module";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		long now = ControlCenter.nowMillis();
		CpsCounter cps = ControlCenter.cps();
		return HudFormat.cpsSummary(state.choice(ModuleSettings.Cps.BUTTONS), cps.countLastSecond(CpsCounter.LEFT, now),
			cps.countLastSecond(CpsCounter.RIGHT, now), out);
	}
}
