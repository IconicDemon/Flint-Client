package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.hud.CpsCounter;
import com.flintclient.client.control.hud.TextOut;
import com.flintclient.client.control.model.CpsButtons;
import com.flintclient.client.control.model.CpsFormat;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.LabelPosition;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.platform.HudData;
import org.jspecify.annotations.Nullable;

/**
 * CPS: clicks in the last second, by default both buttons as "8 · 3 CPS" (left, then right), in the boxed-counter
 * look FPS has (in a box, between brackets or just text). One button, or both added together ("Combined"), shows a
 * single count. Both can also read "8 | 3" or "L 8  R 3". The label goes after, before ("CPS 8 · 3") or nowhere. Only
 * clicks that reached the game are counted (see {@code ControlCenter.onClick}), so a preview on the title screen
 * truthfully shows 0. A Dynamic box holds its width a moment, so clicking past ten a second doesn't make it jump.
 */
final class CpsView extends TextModuleView {
	private final TextOut o = new TextOut();

	CpsView(int surface) {
		super(ModuleId.CPS, surface, ModuleSettings.Cps.STYLE);
	}

	@Override
	int compose(ModuleState state, int mode, long nowNanos, char[] out) {
		int l;
		int r;
		if (mode == SAMPLE) {
			l = 8;
			r = 3;
		} else {
			long now = HudData.nowMillis();
			CpsCounter cps = HudData.cps();
			l = cps.countLastSecond(CpsCounter.LEFT, now);
			r = cps.countLastSecond(CpsCounter.RIGHT, now);
		}
		CpsButtons buttons = state.choice(ModuleSettings.Cps.BUTTONS);
		LabelPosition position = state.choice(ModuleSettings.Cps.LABEL_POSITION);
		o.start(out);
		if (position == LabelPosition.BEFORE) {
			o.text("CPS ");
		}
		if (buttons != CpsButtons.BOTH) {
			o.integer(switch (buttons) {
				case RIGHT -> r;
				case COMBINED -> l + r;
				default -> l;
			});
		} else {
			switch (state.choice(ModuleSettings.Cps.FORMAT)) {
				case LETTERS -> o.text("L ").integer(l).text("  R ").integer(r);
				case BAR -> o.integer(l).text(" | ").integer(r);
				default -> o.integer(l).text(" · ").integer(r);
			}
		}
		if (position == LabelPosition.AFTER) {
			o.text(" CPS");
		}
		return o.length();
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Set by the Label setting of this module";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		long now = HudData.nowMillis();
		CpsCounter cps = HudData.cps();
		return HudFormat.cpsSummary(state.choice(ModuleSettings.Cps.BUTTONS), cps.countLastSecond(CpsCounter.LEFT, now),
			cps.countLastSecond(CpsCounter.RIGHT, now), out);
	}
}
