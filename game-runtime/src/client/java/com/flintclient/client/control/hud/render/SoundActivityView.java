package com.flintclient.client.control.hud.render;

import com.flintclient.client.FlintLog;
import com.flintclient.client.control.feature.SoundActivity;
import com.flintclient.client.control.hud.ScaleSteps;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.MoreMods.Subtitles;
import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.render.Canvas;

/**
 * Draws Subtitles' Nearby Sound Activity ({@link SoundActivity}) in the bottom-right corner of the HUD, on every Minecraft
 * line: a right-aligned stack of captions in Minecraft's own font, the oldest at the bottom, each with a small pixel chevron
 * toward its sound ({@code <} on the left, {@code >} on the right, {@code v} on both sides behind you; none in front, right
 * where you stand, or for a sound with no place), and after a sound heard again how many times, softer ({@code ×2} up to
 * {@code ×99+}). Each row slides in from the right and fades in, dims a little as it ages
 * (as Minecraft's subtitles do), fades out after the display time, and the rows above slide down into its place.
 *
 * <p>Every arrow is worked out each frame from the camera ({@link HudData#soundListener}), so it follows as you turn; each
 * keeps its direction until the sound is clearly past an edge, so nothing flickers. The stack keeps clear of the hotbar
 * where a narrow window would bring the two together, and moves up with Armor's Push subtitles.
 *
 * <p>Cost: nothing while the list is empty (a field read or two); with rows, at most {@link SoundActivity#CAPACITY} of them,
 * each a fill or two and one cached line of text (its glyphs are laid out once, when the row comes). Allocation-free once
 * warm. A failure turns the list off for the session (its own guard), never the rest of the HUD. Render thread.
 */
public final class SoundActivityView {
	private static final HookGuard GUARD = new HookGuard("nearby sound activity",
		(path, error) -> FlintLog.LOGGER.warn("Flint: the {} overlay failed; it's off for this session", path, error));
	private static final HudPen PEN = new HudPen(HudText.HUD);
	private static final HudStyle STYLE = new HudStyle();
	private static final double[] LISTENER = new double[4];

	// Layout, in Minecraft font pixels (times the text scale on screen).
	/** A row's height: the font's 9 with a pixel above and below the line... */
	static final int ROW = 10;
	/** ...and a pixel between rows. */
	static final int ROW_GAP = 1;
	static final int PAD = 2;
	/** An arrow's slot (the widest chevron), and the space between it and the caption. */
	static final int SLOT = 5;
	static final int SLOT_GAP = 3;
	/** Without arrows, a little more space at each end. */
	static final int PLAIN_PAD = 3;
	/** The longest a caption runs before it ends in "…". */
	static final int MAX_TEXT = 220;
	/** Between a caption and its count (a space of Minecraft's font), and how much softer the count is. */
	static final int COUNT_GAP = 4;
	static final float COUNT_DIM = 0.7F;

	/** The GUI units the stack keeps above the screen's bottom where it would meet the hotbar (hunger and air included). */
	static final int CLEAR_OF_HOTBAR = 59;
	/** Half the hotbar with the offhand slot beside it, in GUI units. */
	static final int HOTBAR_HALF = 120;

	/** A new row fades in over this time and slides in from this far to the right (font pixels). */
	static final long ENTER_NANOS = 160_000_000L;
	static final int ENTER_SLIDE = 6;
	/** How quickly a row moves to its new place when one below it goes (time constant). */
	static final double SLIDE_SECONDS = 0.07;
	/** How much a row dims over its display time (Minecraft's subtitles grey out as they age). */
	static final float AGE_DIM = 0.3F;

	/** Minecraft's sixteen chat colours, in Subtitles' colour options' order (after None). */
	private static final int[] CHAT_COLORS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555,
		0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

	private static int resolvedScale = -1;
	private static int resolvedGui = -1;
	private static long lastFrame;

	private SoundActivityView() {
	}

	/** Whether there is anything to draw this frame (the HUD skips its pass entirely when nothing at all is). */
	public static boolean wanted() {
		return GUARD.on() && SoundActivity.get().count() > 0;
	}

	/**
	 * Draws the list on {@code c} (already begun for this HUD frame) on a {@code screenW × screenH} GUI-unit screen at
	 * {@code guiScale} pixels per unit. Never throws: a failure turns the list off for the session.
	 */
	public static void draw(Canvas c, float screenW, float screenH, int guiScale, long now) {
		if (!GUARD.on()) {
			return;
		}
		float before = c.opacity();
		try {
			drawList(c, screenW, screenH, guiScale, now);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			SoundActivity.get().clear();
		} finally {
			c.setOpacity(before);
		}
	}

	private static void drawList(Canvas c, float screenW, float screenH, int guiScale, long now) {
		SoundActivity list = SoundActivity.get();
		if (!SoundActivity.on()) {
			list.clear();
			return;
		}
		ModuleState s = SoundActivity.state();
		long life = SoundActivity.lifeNanos(s);
		list.expire(now, life);
		list.trim(s.integer(Subtitles.MAX_ENTRIES));
		int count = list.count();
		if (count == 0) {
			return;
		}
		double dt = lastFrame == 0 ? 0 : Math.min(0.25, (now - lastFrame) / 1.0E9);
		lastFrame = now;
		resolve(s, guiScale);
		PEN.on(c, STYLE);
		int ts = STYLE.textScale;
		boolean arrows = s.bool(Subtitles.ARROWS);
		boolean background = s.bool(Subtitles.BACKGROUND);
		boolean shadow = s.bool(Subtitles.SHADOW);
		boolean kindColors = s.bool(Subtitles.KIND_COLORS);
		int textRgb = s.rgb(Subtitles.TEXT_COLOR);
		int panel = (Math.round(s.integer(Subtitles.BACKGROUND_OPACITY) * 2.55F) << 24) | s.rgb(Subtitles.BACKGROUND_COLOR) & 0xFFFFFF;
		long shownNanos = SoundActivity.shownNanos(s);
		long fadeNanos = Math.max(1, life - shownNanos);
		boolean pose = arrows && HudData.soundListener(LISTENER);
		float yaw = (float) LISTENER[3];

		// Widths first: the stack's left edge decides whether it must rise above the hotbar.
		int widest = 0;
		for (int i = 0; i < count; i++) {
			SoundActivity.Entry e = list.row(i);
			widest = Math.max(widest, rowWidth(text(e), arrows, ts));
		}
		int right = Math.round((screenW - s.integer(Subtitles.RIGHT_PADDING)) * guiScale);
		float bottomUnits = s.integer(Subtitles.BOTTOM_PADDING) + HudData.subtitlesPush();
		float leftUnits = (float) (right - widest) / guiScale;
		if (bottomUnits < CLEAR_OF_HOTBAR && leftUnits < screenW / 2 + HOTBAR_HALF) {
			bottomUnits = CLEAR_OF_HOTBAR;
		}
		int bottom = Math.round((screenH - bottomUnits) * guiScale);
		int pitch = (ROW + ROW_GAP) * ts;
		double k = dt <= 0 ? 1 : 1 - Math.exp(-dt / SLIDE_SECONDS);
		float base = c.opacity();

		for (int i = 0; i < count; i++) {
			SoundActivity.Entry e = list.row(i);
			RowText caption = text(e);
			// Its place: eases down into the gap a row below leaves.
			float row = e.shownRow;
			row = Float.isNaN(row) || row < i ? i : (float) (row + (i - row) * k);
			if (Math.abs(row - i) < 0.01F) {
				row = i;
			}
			e.shownRow = row;
			long age = now - e.last();
			long since = now - e.first();
			float in = Math.min(1, (float) since / ENTER_NANOS);
			float out = age <= shownNanos ? 1 : 1 - Math.min(1, (float) (age - shownNanos) / fadeNanos);
			float alpha = in * out;
			if (alpha <= 0.004F) {
				continue;
			}
			float eased = 1 - (1 - in) * (1 - in) * (1 - in);
			int slide = Math.round((1 - eased) * ENTER_SLIDE * ts);
			int w = rowWidth(caption, arrows, ts);
			int x1 = right + slide;
			int x0 = x1 - w;
			int y1 = bottom - Math.round(row * pitch);
			int y0 = y1 - ROW * ts;
			int rgb = color(s, e, kindColors, textRgb);
			float dim = 1 - AGE_DIM * Math.min(1, (float) age / Math.max(1, shownNanos));
			int argb = 0xFF000000 | scale(rgb, dim);
			c.setOpacity(base * alpha);
			if (background && (panel >>> 24) > 0) {
				PEN.fill(x0, y0, x1, y1, panel);
			}
			int textX = x0 + (arrows ? PAD + SLOT + SLOT_GAP : PLAIN_PAD) * ts;
			int baseline = y0 + (1 + 7) * ts;
			int end = PEN.text(caption.caption, HudFaces.VALUE, textX, baseline, argb, MAX_TEXT * ts, shadow);
			if (e.count() > 1) {
				// How many times it was heard, softer than the caption.
				PEN.text(caption.count, HudFaces.VALUE, end + COUNT_GAP * ts, baseline, 0xFF000000 | scale(rgb, dim * COUNT_DIM), Integer.MAX_VALUE,
					shadow);
			}
			if (arrows) {
				int way = SoundActivity.NONE;
				if (pose && e.positioned()) {
					way = SoundActivity.direction(LISTENER[0], LISTENER[2], yaw, e.x(), e.z(), e.shownDirection);
				}
				e.shownDirection = way;
				int leftSlot = x0 + PAD * ts;
				int rightSlot = x1 - (PAD + SLOT) * ts;
				switch (way) {
					case SoundActivity.LEFT -> chevron(LEFT_SHAPE, 3, leftSlot + ts, y0 + 2 * ts, ts, argb, shadow);
					case SoundActivity.RIGHT -> chevron(RIGHT_SHAPE, 3, rightSlot + ts, y0 + 2 * ts, ts, argb, shadow);
					case SoundActivity.BEHIND -> {
						chevron(DOWN_SHAPE, 5, leftSlot, y0 + 3 * ts, ts, argb, shadow);
						chevron(DOWN_SHAPE, 5, rightSlot, y0 + 3 * ts, ts, argb, shadow);
					}
					default -> {
					}
				}
			}
		}
	}

	/** Minecraft's font at the chosen text scale (whole pixels per font pixel), re-resolved only when that or the GUI scale changes. */
	private static void resolve(ModuleState s, int guiScale) {
		int step = ScaleSteps.index(s.decimal(Subtitles.SCALE));
		if (step != resolvedScale || guiScale != resolvedGui) {
			resolvedScale = step;
			resolvedGui = guiScale;
			STYLE.set(HudThemeId.CLASSIC, HudColor.DEFAULT, false, 0, 0, true, ScaleSteps.step(step), guiScale);
		}
	}

	/** A row's text, ready to draw: its caption and its count ("×12", up to "×99+"), each a cached line kept with the row. */
	private static final class RowText {
		final HudText caption = new HudText("");
		final HudText count = new HudText("");
	}

	/** The row's text, rebuilt only when a new caption takes the row or its count changes (the count's labels are made once). */
	private static RowText text(SoundActivity.Entry e) {
		RowText t = e.view instanceof RowText r ? r : null;
		if (t == null) {
			t = new RowText();
			e.view = t;
		}
		t.caption.set(e.text());
		t.count.set(SoundActivity.countLabel(e.count()));
		return t;
	}

	/** The caption and the count after it, in pixels (the caption cut short with "…" beyond the longest). */
	private static int textWidth(RowText t, int ts) {
		int width = Math.min(PEN.width(t.caption, HudFaces.VALUE), MAX_TEXT * ts);
		int count = PEN.width(t.count, HudFaces.VALUE);
		return count == 0 ? width : width + COUNT_GAP * ts + count;
	}

	private static int rowWidth(RowText t, boolean arrows, int ts) {
		return textWidth(t, ts) + (arrows ? 2 * (PAD + SLOT + SLOT_GAP) : 2 * PLAIN_PAD) * ts;
	}

	/** The caption's colour: its kind's under Color when Color by kind is on (and the kind has one), else Text color. */
	static int color(ModuleState s, SoundActivity.Entry e, boolean kindColors, int textRgb) {
		if (!kindColors) {
			return textRgb;
		}
		int option = switch (e.kind()) {
			case SoundActivity.KIND_MUSIC -> s.option(Subtitles.MUSIC);
			case SoundActivity.KIND_RECORDS -> s.option(Subtitles.RECORDS);
			case SoundActivity.KIND_WEATHER -> s.option(Subtitles.WEATHER);
			case SoundActivity.KIND_BLOCKS -> s.option(Subtitles.BLOCKS);
			case SoundActivity.KIND_HOSTILE -> s.option(Subtitles.HOSTILE);
			case SoundActivity.KIND_NEUTRAL -> s.option(Subtitles.NEUTRAL);
			case SoundActivity.KIND_PLAYERS -> s.option(Subtitles.PLAYERS);
			case SoundActivity.KIND_AMBIENT -> s.option(Subtitles.AMBIENT);
			case SoundActivity.KIND_VOICE -> s.option(Subtitles.VOICE);
			default -> 0;
		};
		return option <= 0 || option > CHAT_COLORS.length ? textRgb : CHAT_COLORS[option - 1];
	}

	private static int scale(int rgb, float by) {
		int r = Math.round((rgb >> 16 & 255) * by);
		int g = Math.round((rgb >> 8 & 255) * by);
		int b = Math.round((rgb & 255) * by);
		return r << 16 | g << 8 | b;
	}

	// ---------- the chevrons: pixel art in Minecraft's font pixels, row by row ----------

	/** {@code <}: three wide, five tall. */
	static final String[] LEFT_SHAPE = {"..#", ".#.", "#..", ".#.", "..#"};
	/** {@code >}. */
	static final String[] RIGHT_SHAPE = {"#..", ".#.", "..#", ".#.", "#.."};
	/** Behind you: a downward chevron, five wide, three tall. */
	static final String[] DOWN_SHAPE = {"#...#", ".#.#.", "..#.."};

	/** Draws {@code shape} ({@code width} wide) with its top-left at ({@code x}, {@code y}), {@code ts} pixels per font pixel. */
	private static void chevron(String[] shape, int width, int x, int y, int ts, int argb, boolean shadow) {
		if (shadow) {
			int dark = argb & 0xFF000000 | (argb >> 16 & 255) / 4 << 16 | (argb >> 8 & 255) / 4 << 8 | (argb & 255) / 4;
			cells(shape, width, x + ts, y + ts, ts, dark);
		}
		cells(shape, width, x, y, ts, argb);
	}

	private static void cells(String[] shape, int width, int x, int y, int ts, int argb) {
		for (int row = 0; row < shape.length; row++) {
			String line = shape[row];
			for (int col = 0; col < width; col++) {
				if (line.charAt(col) == '#') {
					PEN.fill(x + col * ts, y + row * ts, x + (col + 1) * ts, y + (row + 1) * ts, argb);
				}
			}
		}
	}
}
