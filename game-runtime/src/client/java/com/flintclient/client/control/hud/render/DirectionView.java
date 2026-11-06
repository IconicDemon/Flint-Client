package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.DirectionFormat;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.render.Palette;
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
 *
 * <p>The compass types draw a strip instead: the points around where the player looks (the visible degrees across its
 * width; North in the accent colour), ticks every 15 degrees and longer at the points (Simple: the four main points
 * and a tick at each point; No ticks: letters only), a notch under the middle in the marker colour, and a dot for each
 * of the player's waypoints in this dimension (one off the strip waits at its edge, smaller). Things fade toward the
 * ends.
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
	private int type;
	private float yaw;
	private boolean known;
	private final float[] waypoints = new float[32];
	private final int[] waypointColors = new int[32];
	private int waypointCount;

	DirectionView(int surface) {
		super(ModuleId.DIRECTION, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		format = state.choice(ModuleSettings.Direction.FORMAT);
		boolean detailed = format == DirectionFormat.DETAILED;
		showAxis = detailed && state.bool(ModuleSettings.Direction.SHOW_AXIS);
		showDegrees = detailed && state.bool(ModuleSettings.Direction.SHOW_DEGREES);
		type = state.option(ModuleSettings.Direction.TYPE);
		Object player = mode == PLACEHOLDER ? null : HudData.player();
		waypointCount = 0;
		if (player == null) {
			point = -1;
			degrees.clear();
			// The strip still shows its points, from a fixed heading.
			known = false;
			yaw = 160;
			return;
		}
		yaw = HudFormat.wrapDegrees(HudData.yaw());
		known = true;
		if (type != 0 && state.bool(ModuleSettings.Direction.WAYPOINT_DOTS)) {
			waypointCount = HudData.waypointYaws(waypoints, waypointColors);
		}
		point = HudFormat.point(yaw);
		degrees.set(HudFormat.degrees(yaw));
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		HudStyle s = p.s;
		if (type != 0) {
			width = s.px(state.integer(ModuleSettings.Direction.WIDTH));
			height = s.lineH + (type == 3 ? 0 : s.px(5)) + markerHeight(s, state) + s.px(1);
			return;
		}
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

	private static int markerHeight(HudStyle s, ModuleState state) {
		return Math.max(1, s.px(state.integer(ModuleSettings.Direction.MARKER_SIZE) * 0.6F));
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		if (type != 0) {
			drawCompass(p, state, x, y);
			return;
		}
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

	private void drawCompass(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		boolean ticks = type != 3;
		boolean simple = type == 2;
		boolean shadow = state.bool(ModuleSettings.Direction.SHADOW);
		float half = state.integer(ModuleSettings.Direction.VISIBLE_DEGREES) / 2.0F;
		int mid = x + width / 2;
		float perDegree = width / (2 * half);
		int line = Math.max(1, s.px(0.5F));
		int baseline = p.baseline(y);
		int tickTop = y + s.lineH + s.px(1);
		int tickBottom = tickTop + (ticks ? s.px(4) : 0);
		if (ticks) {
			for (int d = 0; d < 360; d += simple ? 45 : 15) {
				float off = HudFormat.wrapDegrees(d - yaw);
				float fade = fade(off, half);
				if (fade <= 0) {
					continue;
				}
				int px = mid + Math.round(off * perDegree);
				boolean point = d % 45 == 0;
				int top = point ? tickTop : tickTop + s.px(2);
				p.fill(px - line / 2, top, px - line / 2 + line, tickBottom, Palette.withAlpha(s.text, (point ? 0.6F : 0.3F) * fade));
			}
		}
		// Minecraft's yaw: 0 faces south, 90 west, 180 north, 270 east; the points follow HudFormat's order from south.
		for (int i = 0; i < 8; i++) {
			boolean main = (i & 1) == 0;
			if (simple && !main) {
				continue;
			}
			float off = HudFormat.wrapDegrees(i * 45 - yaw);
			float fade = fade(off, half);
			if (fade <= 0) {
				continue;
			}
			HudText letters = LETTERS[i];
			int role = main ? HudFaces.VALUE : HudFaces.LABEL;
			int color = i == 4 ? s.accent : main ? s.text : s.muted;
			int w = p.width(letters, role);
			int px = mid + Math.round(off * perDegree);
			p.text(letters, role, px - w / 2, baseline, Palette.withAlpha(color, (color >>> 24) / 255.0F * fade), Integer.MAX_VALUE, shadow);
		}
		int r = Math.max(2, s.px(2));
		int dotY = ticks ? (tickTop + tickBottom) / 2 : tickTop;
		for (int i = 0; i < waypointCount; i++) {
			float off = HudFormat.wrapDegrees(waypoints[i] - yaw);
			boolean beyond = Math.abs(off) > half;
			int px = mid + Math.round(Math.clamp(off, -half, half) * perDegree);
			int radius = beyond ? Math.max(1, r * 2 / 3) : r;
			px = Math.clamp(px, x + radius, x + width - radius);
			p.box(px - radius, dotY - radius, 2 * radius, 2 * radius, radius, waypointColors[i]);
		}
		// The notch: a small triangle pointing up at the middle, under the ticks.
		int marker = 0xFF000000 | state.rgb(ModuleSettings.Direction.MARKER_COLOR);
		int mh = markerHeight(s, state);
		int top = tickBottom + s.px(1);
		for (int row = 0; row < mh; row++) {
			p.fill(mid - row - line, top + row, mid + row + line, top + row + 1, known ? marker : Palette.withAlpha(marker, 0.5F));
		}
	}

	/** 1 in the middle of the strip, easing to 0 over its last fifth on each side; 0 off it. */
	private static float fade(float off, float half) {
		float t = Math.abs(off) / half;
		if (t >= 1) {
			return 0;
		}
		return t <= 0.8F ? 1 : 1 - (t - 0.8F) / 0.2F;
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
		if (HudData.player() == null) {
			return -1;
		}
		return HudFormat.text(HudFormat.POINTS[HudFormat.point(HudFormat.wrapDegrees(HudData.yaw()))], out, 0);
	}
}
