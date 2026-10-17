package com.flintclient.client.ui.kit;

import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.DigitRun;
import com.flintclient.client.render.Palette;
import com.flintclient.client.render.TextLine;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * A horizontal slider: a 2-unit faint track with an ember fill, a 7-unit knob in the text colour with a thin dark
 * ring, and the value with its unit at the right in tabular digits ({@link Type#META}, so it doesn't jitter
 * while dragging) in a fixed width. Values follow a
 * {@link SliderModel}. Dragging holds the pointer; every change is reported live ({@code onChange}) and
 * the final value once when the drag ends ({@code onCommit}), so a store can be marked dirty without being
 * written per step. Keys: Left/Right step, Shift steps ×10, Page Up/Down step ×10, Home/End jump to the
 * ends (each also commits); Up and Down are left for moving between rows, as in the rest of the kit.
 */
public class Slider extends Node {
	@FunctionalInterface
	public interface Listener {
		void changed(float value);
	}

	private static final float TRACK = 2;
	private static final float KNOB = 7;
	private static final float VALUE_GAP = 7;
	private static final DigitRun DIGITS = new DigitRun(Type.META);

	private final SliderModel model;
	private final int decimals;
	private final @Nullable TextLine unit;
	private final @Nullable Listener onChange;
	private final @Nullable Listener onCommit;
	private final char[] digits = new char[Kit.FORMAT_CHARS];
	private int digitCount;
	private float value;
	private float shown = Float.NaN;
	private float valueWidth = -1;
	private int valueWidthScale = -1;

	public Slider(SliderModel model, float value, int decimals, @Nullable String unit, @Nullable Listener onChange, @Nullable Listener onCommit) {
		this.model = model;
		this.decimals = decimals;
		this.unit = unit == null || unit.isEmpty() ? null : new TextLine(Type.META, unit);
		this.onChange = onChange;
		this.onCommit = onCommit;
		this.value = model.snap(value);
		this.h = HEIGHT;
	}

	public static final float HEIGHT = 14;
	private static final float MIN_VALUE_W = 20;
	/** The width rows give it: a 96-unit track and the value beside it. */
	public static final float WIDTH = 132;

	public float value() {
		return value;
	}

	/** Sets the value without telling the listeners (the model changed elsewhere). */
	public void setValue(float value) {
		this.value = model.snap(value);
	}

	public SliderModel model() {
		return model;
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public boolean focusable() {
		return true;
	}

	@Override
	public float prefWidth() {
		return WIDTH;
	}

	@Override
	public float prefHeight(float width) {
		return HEIGHT;
	}

	@Override
	public String narration() {
		char[] text = new char[Kit.FORMAT_CHARS];
		int n = Kit.format(value, decimals, text);
		String name = accessibleName().isEmpty() ? "Slider" : accessibleName();
		return withReason(name + ", " + new String(text, 0, n) + (unit != null ? unit.text() : ""));
	}

	private void set(float next, boolean commit) {
		float snapped = model.snap(next);
		if (snapped != value) {
			value = snapped;
			if (onChange != null) {
				onChange.changed(value);
			}
		}
		if (commit && onCommit != null) {
			onCommit.changed(value);
		}
	}

	// ---------- geometry ----------

	/** The track's span in units: the bounds minus the value label (wide enough for the longest value). */
	private float trackLeft() {
		return x + KNOB / 2;
	}

	private float trackRight() {
		return x + w - valueWidth() - VALUE_GAP - KNOB / 2;
	}

	private float valueWidth() {
		Canvas c = measure();
		if (valueWidthScale != c.scale()) {
			valueWidthScale = c.scale();
			int widest = 0;
			char[] probe = new char[Kit.FORMAT_CHARS];
			for (float v : new float[] {model.min(), model.max()}) {
				int n = Kit.format(v, decimals, probe);
				widest = Math.max(widest, DIGITS.width(c, probe, n));
			}
			// At least a fixed column, so sliders in a list line their tracks up whatever their values.
			valueWidth = Math.max(MIN_VALUE_W, (float) (widest + (unit != null ? c.width(unit) : 0)) / c.scale());
		}
		return valueWidth;
	}

	private float fractionAt(float mx) {
		float left = trackLeft();
		float right = trackRight();
		return right > left ? (mx - left) / (right - left) : 0;
	}

	// ---------- input ----------

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		if (button != Kit.LEFT_BUTTON) {
			return false;
		}
		set(model.valueAt(fractionAt(mx)), false);
		return true;
	}

	@Override
	public void mouseDrag(float mx, float my) {
		set(model.valueAt(fractionAt(mx)), false);
	}

	@Override
	public void mouseUp(float mx, float my, int button) {
		set(value, true);
	}

	@Override
	public boolean keyDown(KeyEvent event) {
		int steps = event.hasShiftDown() ? 10 : 1;
		int key = event.key();
		if (event.isLeft()) {
			set(model.stepped(value, -steps), true);
		} else if (event.isRight()) {
			set(model.stepped(value, steps), true);
		} else if (key == InputConstants.KEY_HOME) {
			set(model.min(), true);
		} else if (key == InputConstants.KEY_END) {
			set(model.max(), true);
		} else if (key == InputConstants.KEY_PAGEDOWN) {
			set(model.stepped(value, -10), true);
		} else if (key == InputConstants.KEY_PAGEUP) {
			set(model.stepped(value, 10), true);
		} else {
			return false;
		}
		if (screen != null) {
			screen.announce(this);
		}
		return true;
	}

	// ---------- drawing ----------

	@Override
	protected void paint(Canvas c) {
		if (value != shown) {
			shown = value;
			digitCount = Kit.format(value, decimals, digits);
		}
		float left = trackLeft();
		float right = trackRight();
		float t = model.fraction(value);
		float cy = y + h / 2;
		int tx0 = c.px(left);
		int tx1 = c.px(right);
		int ty = c.px(cy - TRACK / 2);
		int th = Math.max(1, c.px(cy + TRACK / 2) - ty);
		float alpha = enabled() ? 1 : 0.4F;
		int knobX = c.px(left + (right - left) * t);
		// Corners and fills: the fill's length changes with every value, which mustn't add an atlas mask each.
		c.roundRectCorners(tx0, ty, Math.max(0, tx1 - tx0), th, th / 2, Palette.withAlpha(Tokens2.SLIDER_TRACK, alpha));
		c.roundRectCorners(tx0, ty, Math.max(0, knobX - tx0), th, th / 2, Palette.withAlpha(Palette.lerp(Tokens2.EMBER, Tokens2.EMBER_HOVER, hover), alpha));
		int knob = c.px(KNOB);
		int knobY = c.px(cy) - knob / 2;
		int ring = Math.max(1, Math.round(c.scale() / 4.0F));
		// The ring first, a pixel larger all round, then the knob: a crisp dark edge on light or ember ground.
		c.roundRect(knobX - knob / 2 - ring, knobY - ring, knob + 2 * ring, knob + 2 * ring, knob / 2 + ring,
			Palette.withAlpha(Kit.prefs().highContrast() ? Tokens2.EMBER : Tokens2.KNOB_RING, alpha));
		c.roundRect(knobX - knob / 2, knobY, knob, knob, knob / 2,
			Palette.withAlpha(Palette.lerp(Tokens2.TEXT_DIM, Tokens2.TEXT, Math.max(hover, press)), alpha));

		int valueRight = px0(c) + pxW(c);
		int baseline = c.baselineFor(Type.META, c.px(cy));
		int ink = enabled() ? Tokens2.TEXT_DIM : Palette.withAlpha(Tokens2.muted(), 0.7F);
		int unitW = unit != null ? c.width(unit) : 0;
		DIGITS.draw(c, digits, digitCount, valueRight - unitW - DIGITS.width(c, digits, digitCount), baseline, ink);
		if (unit != null) {
			c.text(unit, valueRight - unitW, baseline, enabled() ? Tokens2.muted() : Palette.withAlpha(Tokens2.muted(), 0.7F));
		}
		Kit.focusRing(c, x, y, w, h, 3, focus);
	}
}
