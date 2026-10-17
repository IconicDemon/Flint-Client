package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * A choice between a few options, side by side in a quiet 16-unit track (a faint fill, radius 5, padding 2). Each
 * option is as wide as its label plus 8 units. The chosen one sits on a raised pill (a lighter fill and a strong
 * hairline) in the text colour, the others are muted; the pill slides between options in about 120 ms.
 *
 * <p>When it is given less room than its options need ({@link #prefWidth()}), it becomes a cycler instead: the
 * chosen value between ‹ and › ({@link #cyclerWidth()}); a click on either side steps that way. One tab stop either
 * way: Left and Right (and Home/End) choose; Up and Down are left for moving between rows.
 */
public class Segmented extends Node {
	@FunctionalInterface
	public interface Listener {
		void changed(int index);
	}

	public static final float HEIGHT = 18;
	private static final float PAD = 2;
	/** Around each option's label (both sides together). */
	private static final float OPTION_PAD = 10;
	private static final float RADIUS = 6;
	private static final float CHEVRON = 6;
	private static final float CHEVRON_ROOM = 12;
	private static final float SLIDE_TAU = 0.035F;

	private final TextLine[] labels;
	private final @Nullable Listener listener;
	private int selected;
	/** Option widths and offsets from the left edge (units), for {@link #measuredScale}. */
	private final float[] widths;
	private final float[] offsets;
	private float natural;
	private float widest;
	private int measuredScale = -1;
	private float pillX = Float.NaN;
	private float pillW;

	public Segmented(String[] options, int selected, @Nullable Listener listener) {
		this.labels = new TextLine[options.length];
		for (int i = 0; i < options.length; i++) {
			labels[i] = new TextLine(Type.LABEL, options[i]);
		}
		this.widths = new float[options.length];
		this.offsets = new float[options.length];
		this.selected = Math.clamp(selected, 0, Math.max(0, options.length - 1));
		this.listener = listener;
		this.h = HEIGHT;
	}

	public int selected() {
		return selected;
	}

	public int count() {
		return labels.length;
	}

	/** Chooses without telling the listener (the model changed elsewhere). */
	public void setSelected(int index) {
		selected = Math.clamp(index, 0, Math.max(0, labels.length - 1));
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public boolean focusable() {
		return labels.length > 0;
	}

	private void measureOptions() {
		Canvas c = measure();
		if (measuredScale == c.scale()) {
			return;
		}
		measuredScale = c.scale();
		float at = PAD;
		widest = 0;
		for (int i = 0; i < labels.length; i++) {
			float text = (float) c.width(labels[i]) / c.scale();
			widths[i] = Math.round(text + OPTION_PAD);
			offsets[i] = at;
			at += widths[i];
			widest = Math.max(widest, text);
		}
		natural = at + PAD;
	}

	/** Every option side by side. */
	@Override
	public float prefWidth() {
		measureOptions();
		return natural;
	}

	/** The cycler's width: the widest option between its two chevrons. */
	public float cyclerWidth() {
		measureOptions();
		return Math.round(widest + 2 * CHEVRON_ROOM + 2 * PAD);
	}

	@Override
	public float prefHeight(float width) {
		return HEIGHT;
	}

	/** Shown as a cycler: given less room than every option side by side. */
	public boolean cycling() {
		measureOptions();
		return w + 0.01F < natural;
	}

	@Override
	public String narration() {
		String name = accessibleName();
		String choice = labels.length == 0 ? "" : labels[selected].text() + ", " + (selected + 1) + " of " + labels.length;
		return withReason(name.isEmpty() ? choice : name + ", " + choice);
	}

	private void choose(int index) {
		if (labels.length == 0) {
			return;
		}
		int next = Math.floorMod(index, labels.length);
		if (next == selected) {
			return;
		}
		selected = next;
		if (screen != null) {
			screen.playClick();
			if (focused()) {
				screen.announce(this);
			}
		}
		if (listener != null) {
			listener.changed(selected);
		}
	}

	private void step(int direction) {
		if (labels.length == 0) {
			return;
		}
		if (cycling()) {
			// The cycler wraps round, as a dropdown would offer every value.
			choose(selected + direction);
		} else {
			choose(Math.clamp(selected + direction, 0, labels.length - 1));
		}
	}

	private int optionAt(float mx) {
		measureOptions();
		for (int i = 0; i < labels.length; i++) {
			if (mx < x + offsets[i] + widths[i]) {
				return i;
			}
		}
		return labels.length - 1;
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		if (button != Kit.LEFT_BUTTON || labels.length == 0) {
			return false;
		}
		if (cycling()) {
			step(mx < x + w / 3 ? -1 : 1);
		} else {
			choose(optionAt(mx));
		}
		return false;
	}

	@Override
	public boolean keyDown(KeyEvent event) {
		if (event.isLeft()) {
			step(-1);
		} else if (event.isRight()) {
			step(1);
		} else if (event.key() == InputConstants.KEY_HOME) {
			choose(0);
		} else if (event.key() == InputConstants.KEY_END) {
			choose(labels.length - 1);
		} else if (event.isSelection() && cycling()) {
			step(1);
		} else {
			return event.isSelection();
		}
		return true;
	}

	@Override
	protected void paint(Canvas c) {
		if (labels.length == 0) {
			return;
		}
		measureOptions();
		int x0 = px0(c);
		int y0 = py0(c);
		int pw = pxW(c);
		int ph = pxH(c);
		float alpha = enabled() ? 1 : 0.45F;
		c.roundRect(x0, y0, pw, ph, c.px(RADIUS), Palette.withAlpha(Palette.lerp(Tokens2.HOVER, Tokens2.PRESS, cycling() ? hover : 0), alpha));
		int center = y0 + ph / 2;
		if (cycling()) {
			paintCycler(c, x0, pw, center, alpha);
		} else {
			paintOptions(c, y0, ph, center, alpha);
		}
		Kit.focusRing(c, x, y, w, h, RADIUS, focus);
	}

	private void paintOptions(Canvas c, int y0, int ph, int center, float alpha) {
		float targetX = offsets[selected];
		float targetW = widths[selected];
		if (Float.isNaN(pillX)) {
			pillX = targetX;
			pillW = targetW;
		}
		pillX = Kit.approach(pillX, targetX, SLIDE_TAU);
		pillW = Kit.approach(pillW, targetW, SLIDE_TAU);
		int px = c.px(x + pillX);
		int pRight = c.px(x + pillX + pillW);
		int inset = c.px(PAD);
		int pr = c.px(RADIUS - PAD);
		c.roundRect(px, y0 + inset, pRight - px, ph - 2 * inset, pr, Palette.withAlpha(Tokens2.SELECTED, alpha));
		c.ring(px, y0 + inset, pRight - px, ph - 2 * inset, pr, 1, Palette.withAlpha(Tokens2.hairlineStrong(), alpha));
		int hoverIndex = hovered() && screen != null ? optionAt(screen.mouseX()) : -1;
		for (int i = 0; i < labels.length; i++) {
			int left = c.px(x + offsets[i]);
			int right = c.px(x + offsets[i] + widths[i]);
			int ink = i == selected ? Tokens2.TEXT : i == hoverIndex ? Tokens2.TEXT_DIM : Tokens2.muted();
			c.textCentered(labels[i], (left + right) / 2, c.baselineFor(labels[i], center), Palette.withAlpha(ink, alpha), right - left);
		}
	}

	private void paintCycler(Canvas c, int x0, int pw, int center, float alpha) {
		int chevron = c.px(CHEVRON);
		int room = c.px(CHEVRON_ROOM);
		float mx = screen != null ? screen.mouseX() : -1;
		boolean overLeft = hovered() && mx < x + w / 3;
		int leftInk = Palette.withAlpha(overLeft ? Tokens2.TEXT : Tokens2.muted(), alpha);
		int rightInk = Palette.withAlpha(hovered() && !overLeft ? Tokens2.TEXT : Tokens2.muted(), alpha);
		c.icon(Icon.CHEVRON_LEFT, x0 + c.px(PAD) + (room - chevron) / 2, center - chevron / 2, chevron, leftInk);
		c.icon(Icon.CHEVRON_RIGHT, x0 + pw - c.px(PAD) - room + (room - chevron) / 2, center - chevron / 2, chevron, rightInk);
		TextLine label = labels[selected];
		int textMax = Math.max(0, pw - 2 * (room + c.px(PAD)));
		c.textCentered(label, x0 + pw / 2, c.baselineFor(label, center), Palette.withAlpha(Tokens2.TEXT, alpha), textMax);
	}
}
