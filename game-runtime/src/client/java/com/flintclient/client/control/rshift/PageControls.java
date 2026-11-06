package com.flintclient.client.control.rshift;

import static com.flintclient.client.control.rshift.MenuStyle.r;

import com.flintclient.client.control.rshift.MenuStyle.Label;
import com.flintclient.client.control.rshift.MenuStyle.Type;
import com.flintclient.client.platform.KeyInput;
import com.flintclient.client.platform.Keys;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Pressable;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.jspecify.annotations.Nullable;

/**
 * The controls of a module's settings page, in the Right Shift menu's look: a switch, a segmented choice, a slider, a
 * colour swatch and text buttons, sized and coloured as Dawn's controls. Each reads its value from the model every frame and
 * writes it back at once, so a change shows on the HUD in the same frame. Sizes are the menu's reference pixels
 * ({@link MenuStyle#r}, 1.8 per design pixel).
 */
final class PageControls {
	private PageControls() {
	}

	/**
	 * An on/off switch, as Dawn's toggle: 48 × 24, the text at 15% (25% under the pointer) with a grey 16 knob; on, the knob
	 * slides across in green on the green at 15% (25%).
	 */
	static final class Switch extends Pressable {
		static final float W = 86.4F;
		static final float H = 43.2F;
		private final BooleanSupplier on;
		private float t = -1;

		Switch(BooleanSupplier on, Runnable toggle) {
			super(toggle);
			this.on = on;
		}

		@Override
		public float prefWidth() {
			return r(W);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", switch, " + (on.getAsBoolean() ? "on" : "off"));
		}

		@Override
		protected void paint(Canvas c) {
			t = MenuStyle.ease(t, on.getAsBoolean());
			float rad = h / 2;
			int off = Palette.withAlpha(MenuStyle.TEXT, 0.15F + 0.10F * hover);
			int onTrack = Palette.withAlpha(MenuStyle.SUCCESS_TEXT, 0.15F + 0.10F * hover);
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(off, onTrack, t));
			float knob = MenuStyle.d(16);
			float inset = MenuStyle.d(4);
			float kx = x + inset + (w - knob - 2 * inset) * t;
			MenuStyle.box(c, kx, y + (h - knob) / 2, knob, knob, knob / 2, Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.SUCCESS_TEXT, t));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A choice as Dawn's radio: medium buttons (32 tall) 8 apart, the chosen one primary (the accent), the others secondary. */
	static final class Segmented extends Pressable {
		static final float H = 57.6F;
		private static final float PAD = 21.6F;
		private static final float GAP = 14.4F;
		private final Label[] labels;
		private final IntSupplier selected;
		private final IntConsumer pick;
		private final float[] eased;
		private int hot = -1;

		Segmented(String[] options, IntSupplier selected, IntConsumer pick) {
			super(null);
			this.labels = new Label[options.length];
			for (int i = 0; i < options.length; i++) {
				labels[i] = new Label(Type.SEGMENT, options[i]);
			}
			this.eased = new float[options.length];
			java.util.Arrays.fill(eased, -1);
			this.selected = selected;
			this.pick = pick;
		}

		private float segment(Canvas c, int i) {
			return labels[i].width(c) + 2 * r(PAD);
		}

		@Override
		public float prefWidth() {
			Canvas c = measure();
			float total = r(GAP) * (labels.length - 1);
			for (int i = 0; i < labels.length; i++) {
				total += segment(c, i);
			}
			return total;
		}

		private int at(float mx) {
			Canvas c = measure();
			float left = x;
			for (int i = 0; i < labels.length; i++) {
				float sw = segment(c, i);
				if (mx >= left - r(GAP) / 2 && mx < left + sw + r(GAP) / 2) {
					return i;
				}
				left += sw + r(GAP);
			}
			return -1;
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			int i = at(mx);
			if (i >= 0 && enabled()) {
				pick.accept(i);
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public boolean keyDown(KeyInput event) {
			int i = selected.getAsInt();
			if (event.isLeft() && i > 0) {
				pick.accept(i - 1);
				return true;
			}
			if (event.isRight() && i < labels.length - 1) {
				pick.accept(i + 1);
				return true;
			}
			return super.keyDown(event);
		}

		@Override
		public String narration() {
			int i = selected.getAsInt();
			return withReason(accessibleName() + ", " + (i >= 0 ? labels[i].text() : "") + ", " + (i + 1) + " of " + labels.length);
		}

		@Override
		protected void paint(Canvas c) {
			KitScreen screen = screen();
			hot = hovered() && screen != null ? at(screen.mouseX()) : -1;
			float rad = MenuStyle.radius();
			float left = x;
			int chosen = selected.getAsInt();
			for (int i = 0; i < labels.length; i++) {
				float sw = segment(c, i);
				eased[i] = MenuStyle.ease(eased[i], i == chosen);
				float t = eased[i];
				float lit = i == hot ? 1 : 0;
				int fill = Palette.lerp(MenuStyle.SOFT, MenuStyle.ACCENT, t);
				MenuStyle.box(c, left, y, sw, h, rad, fill);
				MenuStyle.halo(c, left, y, sw, h, rad, lit, t >= 0.5F ? Palette.withAlpha(MenuStyle.ACCENT, 0.30F) : Palette.withAlpha(MenuStyle.TEXT, 0.03F));
				int ink = Palette.lerp(i == hot ? MenuStyle.TEXT : MenuStyle.NEUTRAL, MenuStyle.TEXT, t);
				MenuStyle.centered(c, labels[i], left + sw / 2, y + h / 2, ink, sw);
				left += sw + r(GAP);
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A slider with its value: click or drag along the track, or the arrow keys (one step; Shift for five). */
	static final class Slider extends Pressable {
		static final float H = 39.6F;
		/** Dawn's track (200) and value box (40 wide, 16 before the track). */
		private static final float TRACK = 360;
		private static final float BOX = 72;
		private final int min;
		private final int max;
		private final int step;
		private final String unit;
		private final IntSupplier value;
		private final IntConsumer set;
		private final Label shown = new Label(Type.RANGE, "");
		private boolean dragging;
		private boolean onRelease;
		/** The value a drag of an {@link #onRelease} slider is at, set when it is let go (MIN_VALUE: none). */
		private int pending = Integer.MIN_VALUE;
		/** How a value is shown ("0.35", "160 ms"); the default is the number and the unit. */
		private java.util.function.IntFunction<String> format;
		/** The typed text to a value (null when it isn't a number); the default reads a whole number. */
		private java.util.function.Function<String, @Nullable Integer> parse;
		private int shownFor = Integer.MIN_VALUE;
		/** The value box is being typed in (a click on it); Enter applies, Escape cancels. */
		private boolean editing;
		private boolean refocusing;
		private final StringBuilder typed = new StringBuilder();
		private final Label typedLabel = new Label(Type.RANGE, "");
		/** The value box's width, and the scale it was measured at ({@link #box}). */
		private float boxWidth = -1;
		private float boxAt;

		Slider(int min, int max, int step, String unit, IntSupplier value, IntConsumer set) {
			super(null);
			this.min = min;
			this.max = max;
			this.step = step;
			this.unit = unit;
			this.value = value;
			this.set = set;
			this.format = v -> v + unit;
			this.parse = MenuMath::parseWhole;
		}

		/**
		 * A drag shows its value as it goes but sets it only when let go (an option that makes Minecraft rebuild the world or
		 * its GUI each time it changes); a click, the arrow keys and a typed value set it at once.
		 */
		Slider onRelease() {
			this.onRelease = true;
			return this;
		}

		/** Shows values with {@code format} and reads typed ones with {@code parse} (a decimal setting's steps). */
		Slider display(java.util.function.IntFunction<String> format, java.util.function.Function<String, @Nullable Integer> parse) {
			this.format = format;
			this.parse = parse;
			shownFor = Integer.MIN_VALUE;
			boxWidth = -1;
			return this;
		}

		/** The value box: wide enough for the widest value shown ("24 blocks"), never narrower than the reference's. */
		private float box() {
			if (boxWidth < 0 || boxAt != r(1)) {
				Canvas c = measure();
				float widest = Math.max(new Label(Type.RANGE, format.apply(min)).width(c), new Label(Type.RANGE, format.apply(max)).width(c));
				boxWidth = Math.max(r(BOX), widest + r(24));
				boxAt = r(1);
			}
			return boxWidth;
		}

		/** {@code typed} as a value on this slider's steps and range; null when it isn't a number. */
		@Nullable Integer typedValue(String text) {
			Integer raw = parse.apply(text);
			if (raw == null) {
				return null;
			}
			return MenuMath.snap(raw, min, max, step);
		}

		@Override
		public boolean acceptsText() {
			return editing;
		}

		@Override
		public boolean handlesEscape() {
			return editing;
		}

		private void startTyping() {
			editing = true;
			typed.setLength(0);
			String now = format.apply(value.getAsInt());
			// The number only, without its unit.
			for (int i = 0; i < now.length(); i++) {
				char ch = now.charAt(i);
				if (Character.isDigit(ch) || ch == '.' || ch == '-') {
					typed.append(ch);
				} else if (typed.length() > 0) {
					break;
				}
			}
			refocus();
		}

		private void stopTyping(boolean apply) {
			if (!editing) {
				return;
			}
			editing = false;
			if (apply) {
				Integer v = typedValue(typed.toString());
				if (v != null) {
					set.accept(v);
				}
			}
			refocus();
		}

		/** Focus again, so the screen notices that text is (or no longer is) wanted. */
		private void refocus() {
			com.flintclient.client.ui.kit.KitScreen kit = screen();
			if (kit == null) {
				return;
			}
			refocusing = true;
			kit.focus(null);
			kit.focus(this);
			refocusing = false;
		}

		@Override
		protected void focusChanged(boolean focused) {
			if (!focused && editing && !refocusing) {
				stopTyping(true);
			}
			super.focusChanged(focused);
		}

		@Override
		public boolean charTyped(int codepoint) {
			if (!editing) {
				return false;
			}
			if ((Character.isDigit(codepoint) || codepoint == '.' || codepoint == '-' || codepoint == ',') && typed.length() < 9) {
				typed.append(codepoint == ',' ? '.' : (char) codepoint);
			}
			return true;
		}

		@Override
		public float prefWidth() {
			return box() + r(29) + r(TRACK);
		}

		private float trackLeft() {
			return x + box() + r(29);
		}

		private void setFrom(float mx) {
			float k = Math.clamp((mx - trackLeft()) / Math.max(1, w - box() - r(22)), 0, 1);
			int raw = Math.round(min + k * (max - min));
			int snapped = Math.clamp(min + Math.round((raw - min) / (float) step) * step, min, max);
			if (onRelease) {
				pending = snapped;
			} else {
				set.accept(snapped);
			}
		}

		/** The value shown: a drag's while it lasts, else the setting's. */
		private int shownValue() {
			return pending != Integer.MIN_VALUE ? pending : value.getAsInt();
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (enabled() && button == Kit.LEFT_BUTTON && mx >= trackLeft() - r(16)) {
				stopTyping(true);
				dragging = true;
				setFrom(mx);
			} else if (enabled() && button == Kit.LEFT_BUTTON && mx < x + box() && !editing) {
				boolean handled = super.mouseDown(mx, my, button);
				startTyping();
				return handled;
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public void mouseDrag(float mx, float my) {
			if (dragging && enabled()) {
				setFrom(mx);
			}
		}

		@Override
		public void mouseUp(float mx, float my, int button) {
			dragging = false;
			if (pending != Integer.MIN_VALUE) {
				int v = pending;
				pending = Integer.MIN_VALUE;
				if (v != value.getAsInt()) {
					set.accept(v);
				}
			}
			super.mouseUp(mx, my, button);
		}

		@Override
		public boolean keyDown(KeyInput event) {
			if (editing) {
				if (event.isEscape()) {
					stopTyping(false);
				} else if (event.isConfirmation()) {
					stopTyping(true);
				} else if (event.key() == Keys.BACKSPACE && typed.length() > 0) {
					typed.setLength(typed.length() - 1);
				}
				return true;
			}
			if (event.isConfirmation()) {
				startTyping();
				return true;
			}
			int by = event.hasShiftDown() ? 5 * step : step;
			if (event.isLeft() || event.isDown()) {
				set.accept(Math.max(min, value.getAsInt() - by));
				return true;
			}
			if (event.isRight() || event.isUp()) {
				set.accept(Math.min(max, value.getAsInt() + by));
				return true;
			}
			return super.keyDown(event);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", slider, " + (editing ? "typing " + typed : format.apply(value.getAsInt()))
				+ ". Enter to type a value");
		}

		@Override
		protected void paint(Canvas c) {
			int v = shownValue();
			if (v != shownFor) {
				// Made again only when the value changes, never per frame.
				shownFor = v;
				shown.set(format.apply(v));
			}
			float cy = y + h / 2;
			// The value in a small box (the text at 3%, its edge only while typed in), as Dawn's range value.
			MenuStyle.box(c, x, y, box(), h, MenuStyle.d(4), Palette.withAlpha(MenuStyle.TEXT, 0.03F + 0.04F * hover));
			if (editing) {
				MenuStyle.ring(c, x, y, box(), h, MenuStyle.d(4), MenuStyle.edge(), Palette.withAlpha(MenuStyle.ACCENT, 0.7F));
				typedLabel.set(typed.length() == 0 ? "|" : typed + "|");
				MenuStyle.centered(c, typedLabel, x + box() / 2, cy, MenuStyle.ACCENT, box() - r(8));
			} else {
				MenuStyle.centered(c, shown, x + box() / 2, cy, MenuStyle.TEXT, box() - r(8));
			}
			float left = trackLeft();
			float tw = x + w - left;
			float k = max == min ? 0 : (v - min) / (float) (max - min);
			// A hairline track and the thumb: no fill, the thumb's place says the value.
			int line = Math.max(1, c.px(MenuStyle.d(2)));
			int ty = c.px(cy) - line / 2;
			c.fill(c.px(left), ty, c.px(left + tw), ty + line, Palette.withAlpha(MenuStyle.NEUTRAL, 0.20F));
			float knob = MenuStyle.d(12);
			float kx = left + tw * k - knob / 2;
			// Dawn's thumb glows 10 px around under the pointer, the accent at half strength fading out.
			float glow = Math.max(hover, press);
			if (glow > 0.01F) {
				MenuStyle.box(c, kx - MenuStyle.d(5), cy - knob / 2 - MenuStyle.d(5), knob + MenuStyle.d(10), knob + MenuStyle.d(10), knob / 2 + MenuStyle.d(5),
					Palette.withAlpha(MenuStyle.ACCENT, 0.18F * glow));
				MenuStyle.box(c, kx - MenuStyle.d(2.5F), cy - knob / 2 - MenuStyle.d(2.5F), knob + MenuStyle.d(5), knob + MenuStyle.d(5), knob / 2 + MenuStyle.d(2.5F),
					Palette.withAlpha(MenuStyle.ACCENT, 0.25F * glow));
			}
			MenuStyle.box(c, kx, cy - knob / 2, knob, knob, knob / 2, MenuStyle.ACCENT);
			MenuStyle.focusRing(c, left - r(4), y, tw + r(8), h, h / 2, focus);
		}
	}

	/**
	 * A colour: a swatch and its hex value; pressing it opens the colour picker. A colour that can be see-through shows the
	 * Transparent swatch (an empty box with a red line across it) and "Transparent" while it is.
	 */
	static final class ColorSwatch extends Pressable {
		static final float H = 43.2F;
		private final IntSupplier rgb;
		private final @Nullable BooleanSupplier clear;
		private final Label hex = new Label(Type.VALUE, "");

		ColorSwatch(IntSupplier rgb, Runnable open) {
			this(rgb, null, open);
		}

		ColorSwatch(IntSupplier rgb, @Nullable BooleanSupplier clear, Runnable open) {
			super(open);
			this.rgb = rgb;
			this.clear = clear;
		}

		private boolean clear() {
			return clear != null && clear.getAsBoolean();
		}

		/** The swatch, then the widest value it can show (a hex value or "Transparent"), 12 either side; the same for every colour row. */
		@Override
		public float prefWidth() {
			Canvas m = measure();
			float widest = Math.max(new Label(Type.VALUE, "#DDDDDD").width(m), new Label(Type.VALUE, TRANSPARENT).width(m));
			return MenuStyle.d(4) + (r(H) - MenuStyle.d(8)) + MenuStyle.d(12) + widest + MenuStyle.d(12);
		}

		private static final String TRANSPARENT = "Transparent";
		/** Whether the "Transparent" tooltip is set (changed only when the colour becomes or stops being see-through). */
		private boolean tipped;

		@Override
		public String narration() {
			return withReason(accessibleName() + (clear() ? ", transparent" : ", color " + hex.text()) + ", button");
		}

		@Override
		protected void paint(Canvas c) {
			int value = rgb.getAsInt();
			boolean none = clear();
			hex.set(none ? TRANSPARENT : String.format(Locale.ROOT, "#%06X", value & 0xFFFFFF));
			if (none != tipped) {
				tipped = none;
				tooltip(none ? TRANSPARENT : null);
			}
			float rad = MenuStyle.radius();
			MenuStyle.box(c, x, y, w, h, rad, Palette.withAlpha(MenuStyle.TEXT, 0.05F + 0.03F * hover));
			float s = h - MenuStyle.d(8);
			if (none) {
				MenuStyle.transparent(c, x + MenuStyle.d(4), y + MenuStyle.d(4), s, s, MenuStyle.d(4));
			} else {
				MenuStyle.box(c, x + MenuStyle.d(4), y + MenuStyle.d(4), s, s, MenuStyle.d(4), 0xFF000000 | value);
			}
			MenuStyle.ring(c, x + MenuStyle.d(4), y + MenuStyle.d(4), s, s, MenuStyle.d(4), MenuStyle.edge(), MenuStyle.SOFT_EDGE);
			MenuStyle.text(c, hex, x + s + MenuStyle.d(12), y + h / 2, Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/**
	 * A choice with too many options for a segmented control: the chosen option between two arrows. A click on the left
	 * half goes back, on the right half forward (wrapping round); the arrow keys too.
	 */
	static final class Stepper extends Pressable {
		static final float H = 43.2F;
		private static final float W = 360;
		private final Label[] labels;
		private final IntSupplier selected;
		private final IntConsumer pick;

		Stepper(String[] options, IntSupplier selected, IntConsumer pick) {
			super(null);
			this.labels = new Label[options.length];
			for (int i = 0; i < options.length; i++) {
				labels[i] = new Label(Type.SEGMENT, options[i]);
			}
			this.selected = selected;
			this.pick = pick;
		}

		@Override
		public float prefWidth() {
			return r(W);
		}

		private void step(int by) {
			int n = labels.length;
			pick.accept(((selected.getAsInt() + by) % n + n) % n);
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (enabled()) {
				step(mx < x + w / 2 ? -1 : 1);
			}
			return super.mouseDown(mx, my, button);
		}

		@Override
		public boolean keyDown(KeyInput event) {
			if (event.isLeft()) {
				step(-1);
				return true;
			}
			if (event.isRight()) {
				step(1);
				return true;
			}
			return super.keyDown(event);
		}

		@Override
		public String narration() {
			int i = selected.getAsInt();
			return withReason(accessibleName() + ", " + labels[Math.max(0, i)].text() + ", " + (i + 1) + " of " + labels.length);
		}

		@Override
		protected void paint(Canvas c) {
			float rad = MenuStyle.radius();
			MenuStyle.box(c, x, y, w, h, rad, MenuStyle.SOFT);
			float cy = y + h / 2;
			int ink = Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover);
			Glyph.CHEVRON_LEFT.draw(c, x + MenuStyle.d(12), cy, MenuStyle.d(10), ink);
			Glyph.CHEVRON_RIGHT.draw(c, x + w - MenuStyle.d(12), cy, MenuStyle.d(10), ink);
			int i = Math.max(0, selected.getAsInt());
			MenuStyle.centered(c, labels[i], x + w / 2, cy, ink, w - MenuStyle.d(48));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/**
	 * A key: its name in a field ("None" when it has none); pressing it waits for the next key or mouse button. Escape (or
	 * Backspace, or Delete) then leaves the action without a key; a left click anywhere cancels and keeps the key it had;
	 * any other mouse button is bound. The value is Minecraft's version-neutral key name ({@code key.keyboard.c}, "" for
	 * none), written through {@code set}, which saves it (and moves Minecraft's own key mapping when the row mirrors one).
	 */
	static final class KeyBind extends Pressable {
		static final float H = 43.2F;
		private static final float W = 360;
		private final java.util.function.Supplier<String> value;
		private final java.util.function.Consumer<String> set;
		private final Label shown = new Label(Type.VALUE, "");
		private final Label waiting = new Label(Type.VALUE, "Press a key");
		/** Whether the waiting tooltip (what Escape and a click do) is set. */
		private boolean tipped;
		private boolean listening;
		private String shownFor = "\0";

		KeyBind(java.util.function.Supplier<String> value, java.util.function.Consumer<String> set) {
			super(null);
			this.value = value;
			this.set = set;
		}

		@Override
		public float prefWidth() {
			return r(W);
		}

		@Override
		public boolean capturesKeys() {
			return listening;
		}

		@Override
		public void activate() {
			if (!enabled()) {
				return;
			}
			press = 1.0F;
			listening = true;
		}

		@Override
		public boolean keyDown(KeyInput event) {
			if (!listening) {
				return super.keyDown(event);
			}
			listening = false;
			int code = event.key();
			// Escape, Backspace and Delete: no key at all (shown as None).
			if (event.isEscape() || code == Keys.BACKSPACE || code == Keys.DELETE) {
				set.accept("");
			} else {
				set.accept(event.keyName());
			}
			return true;
		}

		@Override
		public boolean captureMouseButton(int button) {
			if (!listening) {
				return false;
			}
			listening = false;
			// A left click (here or anywhere else) cancels: the key stays as it was. Other buttons are bound.
			if (button != Kit.LEFT_BUTTON) {
				set.accept(Keys.mouseName(button));
			}
			return true;
		}

		@Override
		protected void focusChanged(boolean focused) {
			if (!focused) {
				listening = false;
			}
			super.focusChanged(focused);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", key, " + (listening ? "press a key, or Escape for none" : shown.text()));
		}

		@Override
		protected void paint(Canvas c) {
			String key = value.get();
			if (!key.equals(shownFor)) {
				shownFor = key;
				shown.set(KeyNames.display(key));
			}
			// While it waits, its tooltip says what Escape and a click do (the pointer is on it: it was just clicked).
			if (listening != tipped) {
				tipped = listening;
				tooltip(listening ? "Press a key\nEscape: no key (None)\nClick anywhere: keep " + shown.text() : null);
			}
			float rad = MenuStyle.radius();
			// Dawn's control text input: a 1 px edge, the text at 15% (80% under the pointer).
			int edge = listening ? MenuStyle.ACCENT : Palette.withAlpha(MenuStyle.TEXT, 0.15F + 0.65F * hover);
			MenuStyle.ring(c, x, y, w, h, MenuStyle.d(4), Math.max(1, c.px(MenuStyle.d(1))), edge);
			Label text = listening ? waiting : shown;
			MenuStyle.fitted(c, text, x + MenuStyle.d(10), y + h / 2, listening ? MenuStyle.ACCENT : Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover),
				w - MenuStyle.d(20));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/**
	 * A chosen thing (a sound, a list of items): its name in a field with a chevron; pressing it opens the list picker. The
	 * text comes from {@code shown} each frame (cheap: a label only changes when the text does). A list the player searches
	 * and adds to shows its {@code icon} (the magnifier) before the text.
	 */
	static final class PickField extends Pressable {
		private static final float W = 360;
		private final java.util.function.Supplier<String> shown;
		private final Label text = new Label(Type.VALUE, "");
		private final @Nullable Glyph icon;

		PickField(java.util.function.Supplier<String> shown, Runnable open) {
			this(shown, open, null);
		}

		PickField(java.util.function.Supplier<String> shown, Runnable open, @Nullable Glyph icon) {
			super(open);
			this.shown = shown;
			this.icon = icon;
		}

		@Override
		public float prefWidth() {
			return r(W);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", " + text.text() + ", opens a list");
		}

		@Override
		protected void paint(Canvas c) {
			text.set(shown.get());
			float rad = MenuStyle.radius();
			MenuStyle.box(c, x, y, w, h, rad, MenuStyle.SOFT);
			int ink = Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover);
			float textX = x + MenuStyle.d(12);
			Glyph lead = icon;
			if (lead != null) {
				lead.draw(c, x + MenuStyle.d(14), y + h / 2, MenuStyle.d(11), ink);
				textX = x + MenuStyle.d(26);
			}
			MenuStyle.fitted(c, text, textX, y + h / 2, ink, x + w - MenuStyle.d(28) - textX);
			Glyph.CHEVRON_DOWN.draw(c, x + w - MenuStyle.d(12), y + h / 2, MenuStyle.d(10), ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** Looks of {@link TextButton}. */
	enum Look {
		/** The control fill (Reset Settings, Keep). */
		PLAIN,
		/** Ember (an action, or Mod Preview while it is on). */
		ACCENT,
		/** A quiet red (Reset, once asked). */
		DANGER
	}

	/** A text button, optionally with an icon before its text; {@code active} (when given) shows it ember. */
	static final class TextButton extends Pressable {
		static final float H = 72;
		private final Label label;
		private final @Nullable Glyph icon;
		private Look look;
		private @Nullable BooleanSupplier active;
		private float on = -1;

		TextButton(String text, @Nullable Glyph icon, Look look, Runnable action) {
			super(action);
			this.label = new Label(Type.PAGE_BUTTON, text);
			this.icon = icon;
			this.look = look;
			name(text);
		}

		/** New words (and name) for the button: a second press to confirm, say. */
		void setText(String text) {
			label.set(text);
			name(text);
			invalidate();
		}

		void setLook(Look look) {
			this.look = look;
		}

		TextButton active(BooleanSupplier value) {
			this.active = value;
			return this;
		}

		@Override
		public float prefWidth() {
			Canvas c = measure();
			return label.width(c) + 2 * r(27) + (icon != null ? r(30) + r(12) : 0);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", button" + (active != null ? active.getAsBoolean() ? ", on" : ", off" : ""));
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuStyle.ease(on, look == Look.ACCENT || active != null && active.getAsBoolean());
			float rad = MenuStyle.radius();
			int ink;
			if (look == Look.DANGER) {
				MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(Palette.lerp(MenuStyle.RED, 0xFFFFFFFF, 0.08F * hover), 0xFF000000, press * 0.15F));
				MenuStyle.halo(c, x, y, w, h, rad, hover, Palette.withAlpha(MenuStyle.RED, 0.30F));
				ink = 0xFFFFFFFF;
			} else {
				int fill = Palette.lerp(MenuStyle.SOFT, MenuStyle.ACCENT, on);
				MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(fill, 0xFF000000, press * 0.15F));
				MenuStyle.halo(c, x, y, w, h, rad, hover, Palette.lerp(Palette.withAlpha(MenuStyle.TEXT, 0.03F), Palette.withAlpha(MenuStyle.ACCENT, 0.30F), on));
				ink = Palette.lerp(Palette.lerp(MenuStyle.NEUTRAL, MenuStyle.TEXT, hover * 0.6F), MenuStyle.TEXT, on);
			}
			float total = label.width(c) + (icon != null ? r(30) + r(12) : 0);
			float left = x + (w - total) / 2;
			if (icon != null) {
				icon.draw(c, left + r(15), y + h / 2, MenuStyle.d(14), ink);
				left += r(30) + r(12);
			}
			MenuStyle.text(c, label, left, y + h / 2, ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}
}
