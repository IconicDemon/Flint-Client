package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.d;
import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Pressable;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One of the Right Shift menu's quick tiles (the row under EDIT FLINT MODS and HUDS): a dark square with a lone icon, its name
 * under it. Every tile is drawn here, so a new one is only an {@link Entry} in {@code HudPage}'s list.
 *
 * <p>The icon sits in the tile's optical middle ({@link Glyph#drawCentred}), light grey at rest. Under the pointer the tile
 * lifts a little over a soft shadow, its edge warms, the icon turns the accent and a short accent bar grows along its foot.
 * The name is Minecraft's font at the menu's own text size (never the small step), white on a dark rounded chip so it reads
 * over any world; a name too wide for its room breaks onto two lines at a space, and is never cut.
 */
final class QuickTile extends Pressable {
	/** A tile: its name (shown in capitals), its icon and what it opens. */
	record Entry(String name, Glyph icon, Runnable action) {
	}

	/** The tile's side, in reference pixels. */
	static final float SIZE = 180;
	/** The icon's square, as a share of the tile's side. */
	private static final float ICON = 0.4F;
	/** From the tile's foot to the top of its name's chip, in reference pixels. */
	private static final float LABEL_GAP = 16;
	/** The least space between two names' chips, in reference pixels. */
	private static final float CHIP_SPACE = 30;

	private final Glyph icon;
	private final Label full;
	private final Label[] one;
	/** The name broken at its middle space onto two lines; null for a single word. */
	private final Label @Nullable [] split;
	/** From this tile to the next, in layout units (set by the row): the room its name has across, with the chips' space. */
	private float pitch = Float.MAX_VALUE;

	QuickTile(Entry entry) {
		super(entry.action());
		String text = entry.name().toUpperCase(Locale.ROOT);
		this.full = new Label(Type.CHIP, text);
		this.one = new Label[] {full};
		this.split = splitAtMiddleSpace(text);
		this.icon = entry.icon();
		name(entry.name());
	}

	/**
	 * {@code text} broken at the space nearest its middle into two labels; null without a space. Pure but for the labels.
	 */
	private static Label @Nullable [] splitAtMiddleSpace(String text) {
		int at = breakAt(text);
		return at < 0 ? null : new Label[] {new Label(Type.CHIP, text.substring(0, at)), new Label(Type.CHIP, text.substring(at + 1))};
	}

	/** The index of the space nearest the middle of {@code text}, or -1 when it has none. Pure. */
	static int breakAt(String text) {
		int best = -1;
		int middle = text.length() / 2;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == ' ' && (best < 0 || Math.abs(i - middle) < Math.abs(best - middle))) {
				best = i;
			}
		}
		return best;
	}

	/**
	 * The least distance from this tile to the next that holds its name without cutting it: its longest word (it may break
	 * at a space) on its chip, and the space between chips. In layout units.
	 */
	float neededPitch(Canvas c) {
		return leastLabelWidth(c) + 2 * padX(capHeight(c, full)) + r(CHIP_SPACE);
	}

	/** The widest piece the name can't be broken under (its longest word, or all of it in one word), in layout units. */
	private float leastLabelWidth(Canvas c) {
		Label[] two = split;
		if (two == null) {
			return full.width(c);
		}
		return Math.max(two[0].width(c), two[1].width(c));
	}

	/** The name's lines when it may be at most {@code room} layout units wide: one if it fits, else two. */
	private Label[] lines(Canvas c, float room) {
		Label[] two = split;
		if (two == null || full.width(c) <= room) {
			return one;
		}
		return two;
	}

	/** One line of the name's height (its capitals), in layout units. */
	private static float capHeight(Canvas c, Label label) {
		return label.line().capHeightPx(c.scale()) / c.scale();
	}

	private static float lineGap(float cap) {
		return cap * 0.6F;
	}

	private static float padX(float cap) {
		return cap * 1.1F;
	}

	private static float padY(float cap) {
		return cap * 0.55F;
	}

	/** Sets the distance from this tile to the next, in layout units. */
	void pitch(float pitch) {
		this.pitch = pitch;
	}

	@Override
	protected void paint(Canvas c) {
		float rad = MenuStyle.radius();
		float lift = d(3) * hover * (1 - press);
		float top = y - lift;
		// A soft shadow under the lifted tile, so it reads as raised (nothing at rest).
		if (hover > 0.01F) {
			MenuStyle.box(c, x + d(2), y + d(3), w - d(4), h, rad, Palette.withAlpha(0xFF000000, 0.35F * hover));
		}
		MenuStyle.block(c, x, top, w, h, rad, hover, 0);
		MenuStyle.pressed(c, x, top, w, h, rad, press);
		int rest = Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, 0.62F);
		icon.drawCentred(c, x + w / 2, top + h / 2, w * ICON, Palette.lerp(rest, MenuStyle.ACCENT, hover));
		// The accent bar along the tile's foot, growing out from the middle under the pointer.
		if (hover > 0.01F) {
			float bw = w * 0.34F * hover;
			float bh = Math.max(d(3), r(6));
			MenuStyle.box(c, x + w / 2 - bw / 2, top + h - d(9) - bh, bw, bh, bh / 2, Palette.withAlpha(MenuStyle.ACCENT, hover));
		}
		MenuStyle.focusRing(c, x, top, w, h, rad, focus);
		paintName(c);
	}

	/** The name under the tile, on its dark chip (it stays put while the tile lifts). */
	private void paintName(Canvas c) {
		float cap = capHeight(c, full);
		float padX = padX(cap);
		Label[] lines = lines(c, pitch - 2 * padX - r(CHIP_SPACE));
		float gap = lineGap(cap);
		float padY = padY(cap);
		float textW = 0;
		for (Label line : lines) {
			textW = Math.max(textW, line.width(c));
		}
		float chipW = textW + 2 * padX;
		float chipH = lines.length * cap + (lines.length - 1) * gap + 2 * padY;
		float chipTop = y + h + r(LABEL_GAP);
		float cx = x + w / 2;
		MenuStyle.box(c, cx - chipW / 2, chipTop, chipW, chipH, Math.min(d(6), chipH / 2), Palette.withAlpha(0xFF000000, 0.68F + 0.1F * hover));
		// Always on the dark chip, so always near white, whatever the menu's look.
		int ink = Palette.lerp(0xFFE6E6E6, 0xFFFFFFFF, hover);
		float lineTop = chipTop + padY;
		for (Label line : lines) {
			// Never cut: the row gives every name its room (see HudPage's Hub), so the limit here is only a guard.
			MenuStyle.centered(c, line, cx, lineTop + cap / 2, ink, Math.max(chipW, line.width(c) + 1));
			lineTop += cap + gap;
		}
	}
}
