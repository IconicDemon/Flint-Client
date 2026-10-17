package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.CoordinatesLayout;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.platform.Mc263HudData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * Coordinates: the player's position (feet), rounded down like block positions, with 0, 1 or 2 decimals. Stacked
 * (X, Y and Z each on a line, numbers right-aligned in one column) or on one line, with or without the X/Y/Z
 * labels. Optionally the way the player faces ("N", the eight points as letters), at the end of the first line,
 * and the matching position in the other dimension (÷8 in the Overworld, ×8 in the Nether; X and Z only, as Y
 * doesn't change), shown only in those two dimensions. Without a world every number is an em dash.
 *
 * <p>Each number takes at least {@link #MIN_CELLS} tabular digit cells (plus the point and decimals), so walking
 * about near spawn doesn't change the panel's size with every block; numbers are left-aligned in their slot on
 * one line, right-aligned in their column when stacked. The facing column is as wide as its widest point.
 */
final class CoordinatesView extends ModuleView {
	private static final HudText X = new HudText("X", "X:", "x");
	private static final HudText Y = new HudText("Y", "Y:", "y");
	private static final HudText Z = new HudText("Z", "Z:", "z");
	private static final HudText NETHER = new HudText("NETHER", "Nether:", "nether");
	private static final HudText OVERWORLD = new HudText("OVERWORLD", "Overworld:", "overworld");
	private static final HudText[] AXES = {X, Y, Z};
	private static final HudText[] FACING = new HudText[8];
	private static final int OTHER_X = 3;
	private static final int OTHER_Z = 4;
	/** Digit cells a coordinate takes at least: "−123" or "1234". */
	private static final int MIN_CELLS = 4;

	static {
		for (int i = 0; i < 8; i++) {
			FACING[i] = new HudText(HudFormat.SHORT_POINTS[i]);
		}
	}

	/** x, y, z, then the other dimension's x and z. */
	private final Coordinate[] numbers = {new Coordinate(), new Coordinate(), new Coordinate(), new Coordinate(), new Coordinate()};
	private boolean vertical;
	private boolean labels;
	private boolean showFacing;
	private int decimals;
	/** The facing point (index into {@link HudFormat#SHORT_POINTS}), or -1 without a world. */
	private int facing = -1;
	/** The other-dimension line's label (the dimension its numbers are for), or null for no such line. */
	private @Nullable HudText other;
	private int labelColumn;
	private int valueColumn;
	private int facingColumn;

	CoordinatesView(int surface) {
		super(ModuleId.COORDINATES, surface);
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		vertical = state.choice(ModuleSettings.Coordinates.LAYOUT) == CoordinatesLayout.VERTICAL;
		decimals = state.choice(ModuleSettings.Coordinates.DECIMALS).places();
		labels = state.bool(ModuleSettings.Coordinates.LABELS);
		showFacing = state.bool(ModuleSettings.Coordinates.SHOW_DIRECTION);
		LocalPlayer player = mode == PLACEHOLDER ? null : Mc263HudData.player();
		other = null;
		if (player == null) {
			for (Coordinate number : numbers) {
				number.clear();
			}
			facing = -1;
			if (state.bool(ModuleSettings.Coordinates.OTHER_DIMENSION)) {
				// No world: show where the line would be, with dashes.
				other = NETHER;
			}
			return;
		}
		double x = player.getX();
		double z = player.getZ();
		numbers[0].set(x, decimals);
		numbers[1].set(player.getY(), decimals);
		numbers[2].set(z, decimals);
		facing = HudFormat.point(Mth.wrapDegrees(player.getYRot()));
		if (state.bool(ModuleSettings.Coordinates.OTHER_DIMENSION)) {
			int dimension = Mc263HudData.dimension();
			if (dimension == Mc263HudData.OVERWORLD) {
				other = NETHER;
				numbers[OTHER_X].set(x / 8.0, decimals);
				numbers[OTHER_Z].set(z / 8.0, decimals);
			} else if (dimension == Mc263HudData.NETHER) {
				other = OVERWORLD;
				numbers[OTHER_X].set(x * 8.0, decimals);
				numbers[OTHER_Z].set(z * 8.0, decimals);
			}
		}
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		HudStyle s = p.s;
		facingColumn = 0;
		if (showFacing) {
			facingColumn = p.width(HudField.DASH, HudFaces.VALUE);
			for (HudText point : FACING) {
				facingColumn = Math.max(facingColumn, p.width(point, HudFaces.VALUE));
			}
		}
		if (vertical) {
			labelColumn = 0;
			valueColumn = 0;
			if (labels) {
				for (HudText axis : AXES) {
					labelColumn = Math.max(labelColumn, p.width(axis, HudFaces.LABEL));
				}
			}
			for (int i = 0; i < 3; i++) {
				valueColumn = Math.max(valueColumn, numbers[i].slot(p, decimals));
			}
			width = labelColumn + (labelColumn > 0 ? s.space : 0) + valueColumn;
			if (showFacing) {
				width += 2 * s.space + facingColumn;
			}
			if (other != null) {
				width = Math.max(width, otherLine(p, false, 0, 0));
			}
			height = linesHeight(p, other != null ? 4 : 3);
		} else {
			int end = 0;
			for (int i = 0; i < 3; i++) {
				end = group(p, false, i == 0 ? 0 : end + 2 * s.space, 0, AXES[i], numbers[i]);
			}
			if (showFacing) {
				end += 2 * s.space + facingColumn;
			}
			if (other != null) {
				end = otherLine(p, false, end + 3 * s.space, 0);
			}
			width = end;
			height = s.lineH;
		}
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		if (vertical) {
			int top = y;
			int right = x + labelColumn + (labelColumn > 0 ? s.space : 0) + valueColumn;
			for (int i = 0; i < 3; i++) {
				int baseline = p.baseline(top);
				if (labels) {
					p.text(AXES[i], HudFaces.LABEL, x, baseline, s.label);
				}
				numbers[i].draw(p, right - numbers[i].width(p), baseline, s);
				if (i == 0 && showFacing) {
					drawFacing(p, right + 2 * s.space, baseline);
				}
				top += s.lineH + s.lineGap;
			}
			if (other != null) {
				otherLine(p, true, x, p.baseline(top));
			}
		} else {
			int baseline = p.baseline(y);
			int end = x;
			for (int i = 0; i < 3; i++) {
				end = group(p, true, i == 0 ? x : end + 2 * s.space, baseline, AXES[i], numbers[i]);
			}
			if (showFacing) {
				drawFacing(p, end + 2 * s.space, baseline);
				end += 2 * s.space + facingColumn;
			}
			if (other != null) {
				otherLine(p, true, end + 3 * s.space, baseline);
			}
		}
	}

	private void drawFacing(HudPen p, int x, int baseline) {
		if (facing < 0) {
			p.text(HudField.DASH, HudFaces.VALUE, x, baseline, p.s.muted);
		} else {
			p.text(FACING[facing], HudFaces.VALUE, x, baseline, p.s.text);
		}
	}

	@Override
	String emptyReason() {
		return "Shows in a world";
	}

	@Override
	@Nullable String noLabelReason(HudThemeId theme) {
		return "Set by the Labels setting of this module";
	}

	@Override
	int summary(ModuleState state, char[] out, long nowNanos) {
		LocalPlayer player = Mc263HudData.player();
		return player == null ? -1 : HudFormat.coordinatesSummary(player.getX(), player.getY(), player.getZ(), out);
	}

	/** The other dimension's "NETHER X 15 Z −6" (its name always shows: it says what the numbers are); returns where it ends. */
	private int otherLine(HudPen p, boolean draw, int x, int baseline) {
		HudStyle s = p.s;
		int at = draw ? p.text(other, HudFaces.LABEL, x, baseline, s.muted) : x + p.width(other, HudFaces.LABEL);
		at = group(p, draw, at + s.space, baseline, X, numbers[OTHER_X]);
		return group(p, draw, at + 2 * s.space, baseline, Z, numbers[OTHER_Z]);
	}

	/** "X 123": the axis label (when labels are on) and its number in its slot; returns where the slot ends. */
	private int group(HudPen p, boolean draw, int x, int baseline, HudText axis, Coordinate number) {
		HudStyle s = p.s;
		int at = x;
		if (labels) {
			at = draw ? p.text(axis, HudFaces.LABEL, at, baseline, s.label) : at + p.width(axis, HudFaces.LABEL);
			at += s.space;
		}
		if (draw) {
			number.draw(p, at, baseline, s);
		}
		return at + number.slot(p, decimals);
	}

	/** One coordinate's text, rewritten only when the shown value changes. */
	private static final class Coordinate {
		private static final double[] FACTORS = {1, 10, 100, 1000};

		final char[] digits = new char[HudFormat.CHARS];
		int length;
		boolean known;
		private long shown = Long.MIN_VALUE;
		private int shownDecimals = -1;

		void set(double value, int decimals) {
			long key = (long) Math.floor(value * FACTORS[decimals]);
			if (!known || key != shown || decimals != shownDecimals) {
				shown = key;
				shownDecimals = decimals;
				length = HudFormat.coordinate(value, decimals, digits);
			}
			known = true;
		}

		void clear() {
			known = false;
			shown = Long.MIN_VALUE;
		}

		int width(HudPen p) {
			return known ? p.width(digits, length, HudFaces.VALUE) : p.width(HudField.DASH, HudFaces.VALUE);
		}

		/** The room the number takes: its width, but at least {@link #MIN_CELLS} digit cells (plus the point and decimals). */
		int slot(HudPen p, int decimals) {
			return Math.max(width(p), (MIN_CELLS + (decimals > 0 ? decimals + 1 : 0)) * p.cell(HudFaces.VALUE));
		}

		void draw(HudPen p, int x, int baseline, HudStyle s) {
			if (known) {
				p.digits(digits, length, HudFaces.VALUE, x, baseline, s.text);
			} else {
				p.text(HudField.DASH, HudFaces.VALUE, x, baseline, s.muted);
			}
		}
	}
}
