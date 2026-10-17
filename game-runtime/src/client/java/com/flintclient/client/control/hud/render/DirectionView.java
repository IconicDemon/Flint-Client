package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.DirectionFormat;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.platform.Mc263HudData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Direction: the compass point the player faces (eight points), by default its name ("North"), or its letters
 * ("N"), or in detail with the way the coordinates grow that way and the yaw in whole degrees ("North · −Z ·
 * 172°"; the axis and the degrees can each be left out). Minecraft's yaw: 0 faces south (+Z), 90 west (−X), ±180
 * north (−Z), −90 east (+X) ({@code Direction.fromYRot}). Classic labels it "Facing:", as Minecraft's debug text
 * does; a compass point otherwise names itself.
 *
 * <p>The parts are laid out at their own width, so a dot always sits one space from each; the module keeps the
 * width of its widest point, axis and angle, so the panel doesn't change size as the player turns.
 */
final class DirectionView extends ModuleView {
	private static final HudText LABEL = new HudText("", "Facing:", "");
	private static final HudText DEGREE = new HudText("°");
	private static final HudText[] NAMES = new HudText[8];
	private static final HudText[] LETTERS = new HudText[8];
	private static final HudText[] AXES = new HudText[8];

	static {
		for (int i = 0; i < 8; i++) {
			NAMES[i] = new HudText(HudFormat.POINTS[i]);
			LETTERS[i] = new HudText(HudFormat.SHORT_POINTS[i]);
			AXES[i] = new HudText(HudFormat.AXES[i]);
		}
	}

	private final HudField degrees = new HudField(4, HudField.MUTED).affixes(null, DEGREE).tight();
	private int point = -1;
	private DirectionFormat format = DirectionFormat.NAME;
	private boolean showAxis;
	private boolean showDegrees;
	private int labelWidth;

	DirectionView(int surface) {
		super(ModuleId.DIRECTION, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		format = state.choice(ModuleSettings.Direction.FORMAT);
		boolean detailed = format == DirectionFormat.DETAILED;
		showAxis = detailed && state.bool(ModuleSettings.Direction.SHOW_AXIS);
		showDegrees = detailed && state.bool(ModuleSettings.Direction.SHOW_DEGREES);
		LocalPlayer player = mode == PLACEHOLDER ? null : Mc263HudData.player();
		if (player == null) {
			point = -1;
			degrees.clear();
			return;
		}
		float yaw = Mth.wrapDegrees(player.getYRot());
		point = HudFormat.point(yaw);
		degrees.set(HudFormat.degrees(yaw));
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		HudStyle s = p.s;
		labelWidth = s.showLabel && !LABEL.blank(s.theme) ? p.width(LABEL, HudFaces.LABEL) + s.space : 0;
		HudText[] names = format == DirectionFormat.COMPACT ? LETTERS : NAMES;
		int nameColumn = p.width(HudField.DASH, HudFaces.VALUE);
		int axisColumn = 0;
		for (int i = 0; i < 8; i++) {
			nameColumn = Math.max(nameColumn, p.width(names[i], HudFaces.VALUE));
			if (showAxis) {
				axisColumn = Math.max(axisColumn, p.width(AXES[i], HudFaces.VALUE));
			}
		}
		int end = labelWidth + nameColumn;
		if (showAxis) {
			end = separator(p, false, end, 0) + axisColumn;
		}
		if (showDegrees) {
			end = degrees.run(p, false, separator(p, false, end, 0), 0);
		}
		width = end;
		height = s.lineH;
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		int baseline = p.baseline(y);
		if (labelWidth > 0) {
			p.text(LABEL, HudFaces.LABEL, x, baseline, s.label);
		}
		int at = x + labelWidth;
		if (point < 0) {
			at = p.text(HudField.DASH, HudFaces.VALUE, at, baseline, s.muted);
		} else {
			at = p.text((format == DirectionFormat.COMPACT ? LETTERS : NAMES)[point], HudFaces.VALUE, at, baseline, s.text);
		}
		if (showAxis) {
			at = separator(p, true, at, baseline);
			at = point < 0 ? p.text(HudField.DASH, HudFaces.VALUE, at, baseline, s.muted)
				: p.text(AXES[point], HudFaces.VALUE, at, baseline, s.muted);
		}
		if (showDegrees) {
			degrees.run(p, true, separator(p, true, at, baseline), baseline, true);
		}
	}

	@Override
	String emptyReason() {
		return "Shows in a world";
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return LABEL.blank(theme) ? "Only the Classic style labels the direction (\"Facing:\")" : null;
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		LocalPlayer player = Mc263HudData.player();
		if (player == null) {
			return -1;
		}
		return HudFormat.text(HudFormat.POINTS[HudFormat.point(Mth.wrapDegrees(player.getYRot()))], out, 0);
	}
}
