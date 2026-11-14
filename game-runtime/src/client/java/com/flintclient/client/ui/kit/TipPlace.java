package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.TextLine;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Where a tooltip or a popover goes, and how its text wraps, so it always fits the screen whatever the window's size: as wide
 * as it may be but never wider than the screen, as many lines as fit its height, beside its control when asked and there is
 * room, else below it, flipped above when below has no room (to whichever side has more when neither fits it whole), and
 * clamped inside the screen. Pixels throughout. Pure but for {@link #wrap(Canvas, TextLine, int, List)}; shared by the kit's
 * tooltips ({@code KitTooltip}) and Flint's own ({@code FlintTooltip}).
 */
public final class TipPlace {
	private TipPlace() {
	}

	/** The widest a tooltip's text may be: its own most ({@code maxPx}), and the screen less its margins and padding (at least 1). */
	public static int textWidth(int maxPx, int screenW, int gap, int padX) {
		return Math.max(1, Math.min(maxPx, screenW - 2 * gap) - 2 * padX);
	}

	/** How many lines of {@code lineH} fit a screen {@code screenH} tall in a panel with {@code padY} (at least 1). */
	public static int rowsThatFit(int screenH, int gap, int padY, int lineH, int trim) {
		return Math.max(1, (screenH - 2 * gap - 2 * padY + trim) / Math.max(1, lineH));
	}

	/**
	 * Where a {@code w × h} tooltip for the control at {@code ax, ay} ({@code aw × ah}) goes on a {@code screenW × screenH}
	 * screen, {@code gap} from the control and the edges: {@code beside} (a control on the left rail) to its right, centred on
	 * it, when it fits there; otherwise centred under it, above it when it doesn't fit under it, and on the side with more room
	 * when it fits neither; then moved inside the screen. Packed as {@code x << 32 | y} ({@link #x}, {@link #y}).
	 */
	public static long place(int ax, int ay, int aw, int ah, int w, int h, int screenW, int screenH, int gap, boolean beside) {
		int x;
		int y;
		if (beside && ax + aw + gap + w <= screenW - gap) {
			x = ax + aw + gap;
			y = ay + ah / 2 - h / 2;
		} else {
			x = ax + aw / 2 - w / 2;
			int below = ay + ah + gap;
			int above = ay - gap - h;
			if (below + h <= screenH - gap) {
				y = below;
			} else if (above >= gap) {
				y = above;
			} else {
				y = screenH - (ay + ah) >= ay ? below : above;
			}
		}
		x = Math.clamp(x, gap, Math.max(gap, screenW - w - gap));
		y = Math.clamp(y, gap, Math.max(gap, screenH - h - gap));
		return (long) x << 32 | (y & 0xFFFFFFFFL);
	}

	public static int x(long placed) {
		return (int) (placed >> 32);
	}

	public static int y(long placed) {
		return (int) placed;
	}

	/**
	 * {@code text} in lines no wider than {@code maxPx} as {@code width} measures them: words kept together on a line while they
	 * fit, a word wider than that on a line of its own (its drawing cuts it short). An empty text is one empty line.
	 */
	public static List<String> wrapWords(String text, int maxPx, ToIntFunction<String> width) {
		List<String> out = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ", -1)) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (current.isEmpty() || width.applyAsInt(candidate) <= maxPx) {
				current.setLength(0);
				current.append(candidate);
			} else {
				out.add(current.toString());
				current.setLength(0);
				current.append(word);
			}
		}
		out.add(current.toString());
		return out;
	}

	/** Adds {@code line} to {@code out} as is when it fits {@code maxPx}, else wrapped ({@link #wrapWords}) in its own face. */
	public static void wrap(Canvas c, TextLine line, int maxPx, List<TextLine> out) {
		if (c.width(line) <= maxPx) {
			out.add(line);
			return;
		}
		TextLine probe = new TextLine(line.face(), "");
		for (String row : wrapWords(line.text(), maxPx, s -> {
			probe.set(s);
			return c.width(probe);
		})) {
			out.add(new TextLine(line.face(), row));
		}
	}
}
