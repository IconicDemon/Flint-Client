package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.model.CoordinatesLayout;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.platform.HudData;
import org.jspecify.annotations.Nullable;

/**
 * Coordinates: the player's position (feet), rounded down like block positions, with 0, 1 or 2 decimals. Stacked
 * (X, Y and Z each on a line, numbers right-aligned in one column) or on one line, with or without the X/Y/Z
 * labels; X and Z, and Y, can each be left out. Optionally the way the player faces ("N", the eight points as
 * letters, then the angle if wanted), at the end of the first line, and the matching position in the other
 * dimension (÷8 in the Overworld, ×8 in the Nether; X and Z only, as Y doesn't change), shown only in those two
 * dimensions. With Which way they grow on, a + or − after X and Z says how each changes as you walk forward
 * (nothing while you walk almost across that axis). Without a world every number is an em dash.
 *
 * <p>Below (or after, on one line) come the extras, each optional: the block you stand in, the chunk (and where in
 * it, 0 to 15), the biome and how many chunks the game holds; stacked one per line or sharing one. Right alignment
 * lines each group of lines up on the right instead.
 *
 * <p>Each coordinate takes at least {@link #MIN_CELLS} tabular digit cells (plus the point and decimals), so walking
 * about near spawn doesn't change the panel's size with every block; numbers are left-aligned in their slot on
 * one line, right-aligned in their column when stacked. The facing column is as wide as its widest point.
 */
final class CoordinatesView extends ModuleView {
	private static final HudText X = new HudText("X", "X:", "x");
	private static final HudText Y = new HudText("Y", "Y:", "y");
	private static final HudText Z = new HudText("Z", "Z:", "z");
	private static final HudText NETHER = new HudText("NETHER", "Nether:", "nether");
	private static final HudText OVERWORLD = new HudText("OVERWORLD", "Overworld:", "overworld");
	private static final HudText BLOCK = new HudText("BLOCK", "Block:", "block");
	private static final HudText CHUNK = new HudText("CHUNK", "Chunk:", "chunk");
	private static final HudText BIOME = new HudText("BIOME", "Biome:", "biome");
	private static final HudText LOADED = new HudText("CHUNKS", "Chunks:", "chunks");
	private static final HudText PLUS = new HudText("+");
	private static final HudText MINUS = new HudText("−");
	private static final HudText OPEN = new HudText("[");
	private static final HudText CLOSE = new HudText("]");
	private static final HudText DEGREES = new HudText("°");
	private static final HudText[] AXES = {X, Y, Z};
	private static final HudText[] FACING = new HudText[8];
	private static final int OTHER_X = 3;
	private static final int OTHER_Z = 4;
	/** The block position's x; y and z follow. */
	private static final int BLOCK_X = 5;
	private static final int CHUNK_X = 8;
	private static final int CHUNK_Z = 9;
	private static final int IN_X = 10;
	private static final int IN_Z = 11;
	private static final int LOADED_COUNT = 12;
	private static final int ANGLE = 13;
	private static final int EXTRA_BLOCK = 0;
	private static final int EXTRA_CHUNK = 1;
	private static final int EXTRA_BIOME = 2;
	private static final int EXTRA_LOADED = 3;
	private static final int EXTRAS = 4;
	/** Digit cells a coordinate takes at least: "−123" or "1234". */
	private static final int MIN_CELLS = 4;
	/** How far forward must lean along an axis (sin 12°) before its sign shows: walking across it, it hardly changes. */
	private static final double SIGN_LEAN = 0.2;

	static {
		for (int i = 0; i < 8; i++) {
			FACING[i] = new HudText(HudFormat.SHORT_POINTS[i]);
		}
	}

	/** x, y, z, the other dimension's x and z, the block position, the chunk, the place in it, loaded chunks, the angle. */
	private final Coordinate[] numbers = new Coordinate[14];
	private final HudText biome = new HudText("");
	private boolean biomeKnown;
	private final boolean[] axis = new boolean[3];
	private final boolean[] extra = new boolean[EXTRAS];
	private boolean vertical;
	private boolean labels;
	private boolean showFacing;
	private boolean showAngle;
	private boolean signs;
	private boolean inChunk;
	private boolean chunkKey;
	private boolean biomeKey;
	private boolean loadedKey;
	private boolean oneLine;
	private boolean rightAligned;
	private int decimals;
	/** The facing, angle, signs and brackets' color, or 0 for the theme's. */
	private int extraColor;
	private int signX;
	private int signZ;
	/** The facing point (index into {@link HudFormat#SHORT_POINTS}), or -1 without a world. */
	private int facing = -1;
	/** The other-dimension line's label (the dimension its numbers are for), or null for no such line. */
	private @Nullable HudText other;
	private int rows;
	private int labelColumn;
	private int valueColumn;
	private int pointColumn;
	private int facingColumn;
	private int signColumn;

	CoordinatesView(int surface) {
		super(ModuleId.COORDINATES, surface);
		for (int i = 0; i < numbers.length; i++) {
			numbers[i] = new Coordinate();
		}
	}

	@Override
	void update(ModuleState state, int mode, long nowNanos) {
		vertical = state.choice(ModuleSettings.Coordinates.LAYOUT) == CoordinatesLayout.VERTICAL;
		decimals = state.choice(ModuleSettings.Coordinates.DECIMALS).places();
		labels = state.bool(ModuleSettings.Coordinates.LABELS);
		showFacing = state.bool(ModuleSettings.Coordinates.SHOW_DIRECTION);
		showAngle = showFacing && state.bool(ModuleSettings.Coordinates.SHOW_ANGLE);
		boolean coordinates = state.bool(ModuleSettings.Coordinates.SHOW_COORDINATES);
		axis[0] = coordinates && state.bool(ModuleSettings.Coordinates.SHOW_XZ);
		axis[1] = coordinates && state.bool(ModuleSettings.Coordinates.SHOW_Y);
		axis[2] = axis[0];
		signs = axis[0] && state.bool(ModuleSettings.Coordinates.MOVEMENT);
		extra[EXTRA_BLOCK] = state.bool(ModuleSettings.Coordinates.BLOCK);
		extra[EXTRA_CHUNK] = state.bool(ModuleSettings.Coordinates.CHUNK);
		extra[EXTRA_BIOME] = state.bool(ModuleSettings.Coordinates.BIOME);
		extra[EXTRA_LOADED] = state.bool(ModuleSettings.Coordinates.LOADED);
		inChunk = extra[EXTRA_CHUNK] && state.bool(ModuleSettings.Coordinates.IN_CHUNK);
		chunkKey = state.bool(ModuleSettings.Coordinates.CHUNK_KEY);
		biomeKey = state.bool(ModuleSettings.Coordinates.BIOME_KEY);
		loadedKey = state.bool(ModuleSettings.Coordinates.LOADED_KEY);
		oneLine = state.bool(ModuleSettings.Coordinates.ONE_LINE);
		rightAligned = state.option(ModuleSettings.Coordinates.ALIGNMENT) == 1;
		HudColor color = state.choice(ModuleSettings.Coordinates.FORMAT_COLOR);
		extraColor = color.isDefault() ? 0 : color.argb();
		Object player = mode == PLACEHOLDER ? null : HudData.player();
		other = null;
		if (player == null) {
			for (Coordinate number : numbers) {
				number.clear();
			}
			biomeKnown = false;
			facing = -1;
			signX = 0;
			signZ = 0;
			if (state.bool(ModuleSettings.Coordinates.OTHER_DIMENSION)) {
				// No world: show where the line would be, with dashes.
				other = NETHER;
			}
			return;
		}
		double x = HudData.x();
		double y = HudData.y();
		double z = HudData.z();
		numbers[0].set(x, decimals);
		numbers[1].set(y, decimals);
		numbers[2].set(z, decimals);
		float yaw = HudFormat.wrapDegrees(HudData.yaw());
		facing = HudFormat.point(yaw);
		if (showAngle) {
			numbers[ANGLE].set(yaw, 1);
		}
		if (signs) {
			double radians = Math.toRadians(yaw);
			signX = sign(-Math.sin(radians));
			signZ = sign(Math.cos(radians));
		}
		if (extra[EXTRA_BLOCK]) {
			numbers[BLOCK_X].set(x, 0);
			numbers[BLOCK_X + 1].set(y, 0);
			numbers[BLOCK_X + 2].set(z, 0);
		}
		if (extra[EXTRA_CHUNK]) {
			int blockX = (int) Math.floor(x);
			int blockZ = (int) Math.floor(z);
			numbers[CHUNK_X].set(blockX >> 4, 0);
			numbers[CHUNK_Z].set(blockZ >> 4, 0);
			numbers[IN_X].set(blockX & 15, 0);
			numbers[IN_Z].set(blockZ & 15, 0);
		}
		if (extra[EXTRA_BIOME]) {
			String name = HudData.biome();
			biomeKnown = name != null;
			if (name != null) {
				biome.set(name);
			}
		}
		if (extra[EXTRA_LOADED]) {
			numbers[LOADED_COUNT].set(HudData.loadedChunks(), 0);
		}
		if (state.bool(ModuleSettings.Coordinates.OTHER_DIMENSION)) {
			int dimension = HudData.dimension();
			if (dimension == HudData.OVERWORLD) {
				other = NETHER;
				numbers[OTHER_X].set(x / 8.0, decimals);
				numbers[OTHER_Z].set(z / 8.0, decimals);
			} else if (dimension == HudData.NETHER) {
				other = OVERWORLD;
				numbers[OTHER_X].set(x * 8.0, decimals);
				numbers[OTHER_Z].set(z * 8.0, decimals);
			}
		}
	}

	private static int sign(double lean) {
		return lean > SIGN_LEAN ? 1 : lean < -SIGN_LEAN ? -1 : 0;
	}

	@Override
	void measure(HudPen p, ModuleState state) {
		HudStyle s = p.s;
		pointColumn = 0;
		facingColumn = 0;
		if (showFacing) {
			pointColumn = p.width(HudField.DASH, HudFaces.VALUE);
			for (HudText point : FACING) {
				pointColumn = Math.max(pointColumn, p.width(point, HudFaces.VALUE));
			}
			facingColumn = pointColumn;
			if (showAngle) {
				facingColumn += s.space + numbers[ANGLE].slot(p, 1, MIN_CELLS) + p.width(DEGREES, HudFaces.VALUE);
			}
		}
		signColumn = signs ? Math.max(p.width(PLUS, HudFaces.VALUE), p.width(MINUS, HudFaces.VALUE)) : 0;
		if (vertical) {
			labelColumn = 0;
			valueColumn = 0;
			rows = 0;
			for (int i = 0; i < 3; i++) {
				if (axis[i]) {
					rows++;
					if (labels) {
						labelColumn = Math.max(labelColumn, p.width(AXES[i], HudFaces.LABEL));
					}
					valueColumn = Math.max(valueColumn, numbers[i].slot(p, decimals, MIN_CELLS));
				}
			}
			width = tableWidth(s);
			int lines = rows == 0 && showFacing ? 1 : rows;
			if (other != null) {
				width = Math.max(width, otherLine(p, false, 0, 0));
				lines++;
			}
			if (oneLine) {
				int extras = extras(p, false, 0, 0);
				if (extras > 0) {
					width = Math.max(width, extras);
					lines++;
				}
			} else {
				for (int k = 0; k < EXTRAS; k++) {
					if (extra[k]) {
						width = Math.max(width, extra(p, false, 0, 0, k));
						lines++;
					}
				}
			}
			height = linesHeight(p, Math.max(1, lines));
		} else {
			width = line(p, false, 0, 0);
			height = s.lineH;
		}
	}

	/** The stacked X/Y/Z lines' width: labels, numbers, signs, then the facing; just the facing when no line shows. */
	private int tableWidth(HudStyle s) {
		if (rows == 0) {
			return showFacing ? facingColumn : 0;
		}
		int w = labelColumn + (labelColumn > 0 ? s.space : 0) + valueColumn;
		if (signs) {
			w += signGap(s) + signColumn;
		}
		if (showFacing) {
			w += 2 * s.space + facingColumn;
		}
		return w;
	}

	private static int signGap(HudStyle s) {
		return Math.max(1, s.space / 2);
	}

	@Override
	void draw(HudPen p, ModuleState state, int x, int y) {
		HudStyle s = p.s;
		if (!vertical) {
			line(p, true, x, p.baseline(y));
			return;
		}
		int top = y;
		int table = tableWidth(s);
		int left = x + (rightAligned ? width - table : 0);
		int right = left + labelColumn + (labelColumn > 0 ? s.space : 0) + valueColumn;
		boolean first = true;
		for (int i = 0; i < 3; i++) {
			if (!axis[i]) {
				continue;
			}
			int baseline = p.baseline(top);
			if (labels) {
				p.text(AXES[i], HudFaces.LABEL, left, baseline, s.label);
			}
			numbers[i].draw(p, right - numbers[i].width(p), baseline, s.text, s);
			int after = right;
			if (signs) {
				if (i != 1) {
					drawSign(p, i == 0 ? signX : signZ, after + signGap(s), baseline);
				}
				after += signGap(s) + signColumn;
			}
			if (first && showFacing) {
				drawFacing(p, after + 2 * s.space, baseline);
			}
			first = false;
			top += s.lineH + s.lineGap;
		}
		if (first && showFacing) {
			drawFacing(p, left, p.baseline(top));
			top += s.lineH + s.lineGap;
		}
		if (other != null) {
			int w = otherLine(p, false, 0, 0);
			otherLine(p, true, x + (rightAligned ? width - w : 0), p.baseline(top));
			top += s.lineH + s.lineGap;
		}
		if (oneLine) {
			int w = extras(p, false, 0, 0);
			if (w > 0) {
				extras(p, true, x + (rightAligned ? width - w : 0), p.baseline(top));
			}
			return;
		}
		for (int k = 0; k < EXTRAS; k++) {
			if (extra[k]) {
				int w = extra(p, false, 0, 0, k);
				extra(p, true, x + (rightAligned ? width - w : 0), p.baseline(top), k);
				top += s.lineH + s.lineGap;
			}
		}
	}

	/** Everything on one line: X, Y and Z (each sign right after its number), the facing, the other dimension, the extras. */
	private int line(HudPen p, boolean draw, int x, int baseline) {
		HudStyle s = p.s;
		int end = x;
		boolean any = false;
		for (int i = 0; i < 3; i++) {
			if (!axis[i]) {
				continue;
			}
			end = group(p, draw, any ? end + 2 * s.space : end, baseline, AXES[i], numbers[i]);
			if (signs && i != 1) {
				// The slot keeps its room; the sign sits right after the number in it.
				int numberEnd = end - numbers[i].slot(p, decimals, MIN_CELLS) + numbers[i].width(p);
				if (draw) {
					drawSign(p, i == 0 ? signX : signZ, numberEnd + signGap(s), baseline);
				}
				end += signGap(s) + signColumn;
			}
			any = true;
		}
		if (showFacing) {
			int at = any ? end + 2 * s.space : end;
			if (draw) {
				drawFacing(p, at, baseline);
			}
			end = at + facingColumn;
			any = true;
		}
		if (other != null) {
			end = otherLine(p, draw, any ? end + 3 * s.space : end, baseline);
			any = true;
		}
		return extras(p, draw, any ? end + 3 * s.space : end, baseline, end);
	}

	/** The shown extras one after another; returns where they end ({@code x} when none shows). */
	private int extras(HudPen p, boolean draw, int x, int baseline) {
		return extras(p, draw, x, baseline, x);
	}

	/** As {@link #extras(HudPen, boolean, int, int)}, returning {@code none} when no extra shows. */
	private int extras(HudPen p, boolean draw, int x, int baseline, int none) {
		int at = x;
		boolean any = false;
		for (int k = 0; k < EXTRAS; k++) {
			if (extra[k]) {
				at = extra(p, draw, any ? at + 3 * p.s.space : at, baseline, k);
				any = true;
			}
		}
		return any ? at : none;
	}

	/** One extra: "BLOCK 47 67 57", "CHUNK 2 3 [15 9]", "BIOME Snowy Taiga" or "CHUNKS 441"; returns where it ends. */
	private int extra(HudPen p, boolean draw, int x, int baseline, int kind) {
		HudStyle s = p.s;
		int at;
		switch (kind) {
			case EXTRA_BLOCK -> {
				at = key(p, draw, x, baseline, BLOCK, true);
				for (int i = 0; i < 3; i++) {
					at = number(p, draw, i == 0 ? at : at + s.space, baseline, numbers[BLOCK_X + i], s.text);
				}
			}
			case EXTRA_CHUNK -> {
				at = key(p, draw, x, baseline, CHUNK, chunkKey);
				at = number(p, draw, at, baseline, numbers[CHUNK_X], s.text);
				at = number(p, draw, at + s.space, baseline, numbers[CHUNK_Z], s.text);
				if (inChunk) {
					int bracket = extraColor != 0 ? extraColor : s.muted;
					at += s.space;
					at = draw ? p.text(OPEN, HudFaces.VALUE, at, baseline, bracket) : at + p.width(OPEN, HudFaces.VALUE);
					at = number(p, draw, at, baseline, numbers[IN_X], bracket);
					at = number(p, draw, at + s.space, baseline, numbers[IN_Z], bracket);
					at = draw ? p.text(CLOSE, HudFaces.VALUE, at, baseline, bracket) : at + p.width(CLOSE, HudFaces.VALUE);
				}
			}
			case EXTRA_BIOME -> {
				at = key(p, draw, x, baseline, BIOME, biomeKey);
				HudText name = biomeKnown ? biome : HudField.DASH;
				at = draw ? p.text(name, HudFaces.VALUE, at, baseline, biomeKnown ? s.text : s.muted) : at + p.width(name, HudFaces.VALUE);
			}
			default -> {
				at = key(p, draw, x, baseline, LOADED, loadedKey);
				at = number(p, draw, at, baseline, numbers[LOADED_COUNT], s.text);
			}
		}
		return at;
	}

	/** An extra's label and the space after it, when shown; returns where its value starts. */
	private static int key(HudPen p, boolean draw, int x, int baseline, HudText key, boolean shown) {
		if (!shown) {
			return x;
		}
		int end = draw ? p.text(key, HudFaces.LABEL, x, baseline, p.s.label) : x + p.width(key, HudFaces.LABEL);
		return end + p.s.space;
	}

	private static int number(HudPen p, boolean draw, int x, int baseline, Coordinate number, int color) {
		if (draw) {
			number.draw(p, x, baseline, color, p.s);
		}
		return x + number.width(p);
	}

	private void drawSign(HudPen p, int sign, int x, int baseline) {
		if (sign != 0) {
			p.text(sign > 0 ? PLUS : MINUS, HudFaces.VALUE, x, baseline, extraColor != 0 ? extraColor : p.s.muted);
		}
	}

	private void drawFacing(HudPen p, int x, int baseline) {
		HudStyle s = p.s;
		if (facing < 0) {
			p.text(HudField.DASH, HudFaces.VALUE, x, baseline, s.muted);
			return;
		}
		int color = extraColor != 0 ? extraColor : s.text;
		p.text(FACING[facing], HudFaces.VALUE, x, baseline, color);
		if (showAngle) {
			int at = number(p, true, x + pointColumn + s.space, baseline, numbers[ANGLE], color);
			p.text(DEGREES, HudFaces.VALUE, at, baseline, color);
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
		return HudData.player() == null ? -1 : HudFormat.coordinatesSummary(HudData.x(), HudData.y(), HudData.z(), out);
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
			number.draw(p, at, baseline, s.text, s);
		}
		return at + number.slot(p, decimals, MIN_CELLS);
	}

	/** One number's text, rewritten only when the shown value changes. */
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

		/** The room the number takes: its width, but at least {@code minCells} digit cells (plus the point and decimals). */
		int slot(HudPen p, int decimals, int minCells) {
			return Math.max(width(p), (minCells + (decimals > 0 ? decimals + 1 : 0)) * p.cell(HudFaces.VALUE));
		}

		void draw(HudPen p, int x, int baseline, int color, HudStyle s) {
			if (known) {
				p.digits(digits, length, HudFaces.VALUE, x, baseline, color);
			} else {
				p.text(HudField.DASH, HudFaces.VALUE, x, baseline, s.muted);
			}
		}
	}
}
