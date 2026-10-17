package com.flintclient.client.control.menu;

import static com.flintclient.client.control.menu.MenuStyle.r;

import com.flintclient.client.control.menu.MenuStyle.Label;
import com.flintclient.client.control.menu.MenuStyle.Type;
import com.flintclient.client.render.Canvas;
import com.flintclient.client.render.Icon;
import com.flintclient.client.render.Palette;
import com.flintclient.client.ui.kit.Kit;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Pressable;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.input.KeyEvent;
import org.jspecify.annotations.Nullable;

/**
 * The controls of a module's settings page, in the Right Shift menu's look: a switch, a segmented choice, a slider, a
 * colour swatch and text buttons. Each reads its value from the model every frame and writes it back at once, so a
 * change shows in the preview and on the HUD in the same frame. Sizes are the menu's reference pixels ({@link MenuStyle#r}).
 */
final class PageControls {
	private PageControls() {
	}

	/** An on/off switch: an ember track with a white knob when on. */
	static final class Switch extends Pressable {
		static final float W = 76;
		static final float H = 42;
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
			t = MenuControls.ease(t, on.getAsBoolean());
			float rad = h / 2;
			int track = Palette.lerp(Palette.lerp(MenuStyle.TRACK_OFF, 0xFF48433C, hover * 0.6F), MenuStyle.ACCENT, t);
			MenuStyle.box(c, x, y, w, h, rad, track);
			float knob = r(32);
			float inset = (h - knob) / 2;
			float kx = x + inset + (w - knob - 2 * inset) * t;
			MenuStyle.box(c, kx, y + inset, knob, knob, knob / 2, Palette.lerp(MenuStyle.KNOB_OFF, 0xFFFFFFFF, t));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A segmented choice: one pill per option, the chosen one ember. */
	static final class Segmented extends Pressable {
		static final float H = 52;
		private static final float PAD = 24;
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
			float total = r(4) * 2;
			for (int i = 0; i < labels.length; i++) {
				total += segment(c, i);
			}
			return total;
		}

		private int at(float mx) {
			Canvas c = measure();
			float left = x + r(4);
			for (int i = 0; i < labels.length; i++) {
				float sw = segment(c, i);
				if (mx >= left && mx < left + sw) {
					return i;
				}
				left += sw;
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
		public boolean keyDown(KeyEvent event) {
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
			float rad = r(14);
			MenuStyle.box(c, x, y, w, h, rad, MenuStyle.SEGMENTS);
			float left = x + r(4);
			int chosen = selected.getAsInt();
			for (int i = 0; i < labels.length; i++) {
				float sw = segment(c, i);
				eased[i] = MenuControls.ease(eased[i], i == chosen);
				float t = eased[i];
				int idle = i == hot ? MenuStyle.SEGMENT_HOVER : 0;
				int fill = Palette.lerp(idle, MenuStyle.ACCENT, t);
				if ((fill >>> 24) != 0) {
					MenuStyle.box(c, left, y + r(4), sw, h - r(8), rad - r(3), fill);
				}
				int ink = Palette.lerp(i == hot ? MenuStyle.TAB_TEXT_SELECTED : MenuStyle.CHIP_TEXT, MenuStyle.ON_ACCENT, t);
				MenuStyle.centered(c, labels[i], left + sw / 2, y + h / 2, ink, sw);
				left += sw;
			}
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/** A slider with its value: click or drag along the track, or the arrow keys (one step; Shift for five). */
	static final class Slider extends Pressable {
		static final float H = 48;
		private static final float TRACK = 250;
		private static final float BOX = 96;
		private final int min;
		private final int max;
		private final int step;
		private final String unit;
		private final IntSupplier value;
		private final IntConsumer set;
		private final Label shown = new Label(Type.VALUE, "");
		private boolean dragging;
		/** How a value is shown ("0.35", "160 ms"); the default is the number and the unit. */
		private java.util.function.IntFunction<String> format;
		/** The typed text to a value (null when it isn't a number); the default reads a whole number. */
		private java.util.function.Function<String, @Nullable Integer> parse;
		private int shownFor = Integer.MIN_VALUE;
		/** The value box is being typed in (a click on it); Enter applies, Escape cancels. */
		private boolean editing;
		private boolean refocusing;
		private final StringBuilder typed = new StringBuilder();
		private final Label typedLabel = new Label(Type.VALUE, "");

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

		/** Shows values with {@code format} and reads typed ones with {@code parse} (a decimal setting's steps). */
		Slider display(java.util.function.IntFunction<String> format, java.util.function.Function<String, @Nullable Integer> parse) {
			this.format = format;
			this.parse = parse;
			shownFor = Integer.MIN_VALUE;
			return this;
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
			return r(BOX) + r(22) + r(TRACK);
		}

		private float trackLeft() {
			return x + r(BOX) + r(22);
		}

		private void setFrom(float mx) {
			float k = Math.clamp((mx - trackLeft()) / Math.max(1, w - r(BOX) - r(22)), 0, 1);
			int raw = Math.round(min + k * (max - min));
			int snapped = min + Math.round((raw - min) / (float) step) * step;
			set.accept(Math.clamp(snapped, min, max));
		}

		@Override
		public boolean mouseDown(float mx, float my, int button) {
			if (enabled() && button == Kit.LEFT_BUTTON && mx >= trackLeft() - r(16)) {
				stopTyping(true);
				dragging = true;
				setFrom(mx);
			} else if (enabled() && button == Kit.LEFT_BUTTON && mx < x + r(BOX) && !editing) {
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
			super.mouseUp(mx, my, button);
		}

		@Override
		public boolean keyDown(KeyEvent event) {
			if (editing) {
				if (event.isEscape()) {
					stopTyping(false);
				} else if (event.isConfirmation()) {
					stopTyping(true);
				} else if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE && typed.length() > 0) {
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
			int v = value.getAsInt();
			if (v != shownFor) {
				// Made again only when the value changes, never per frame.
				shownFor = v;
				shown.set(format.apply(v));
			}
			float cy = y + h / 2;
			MenuStyle.framed(c, x, y, r(BOX), h, r(12), MenuStyle.FIELD, editing ? MenuStyle.ACCENT
				: Palette.lerp(MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, hover * 0.6F));
			if (editing) {
				typedLabel.set(typed.length() == 0 ? "|" : typed + "|");
				MenuStyle.centered(c, typedLabel, x + r(BOX) / 2, cy, MenuStyle.ACCENT, r(BOX) - r(8));
			} else {
				MenuStyle.centered(c, shown, x + r(BOX) / 2, cy, MenuStyle.ROW_TEXT, r(BOX) - r(8));
			}
			float left = trackLeft();
			float tw = x + w - left;
			float k = max == min ? 0 : (v - min) / (float) (max - min);
			float th = r(8);
			MenuStyle.box(c, left, cy - th / 2, tw, th, th / 2, MenuStyle.TRACK_OFF);
			MenuStyle.box(c, left, cy - th / 2, Math.max(th, tw * k), th, th / 2, MenuStyle.ACCENT);
			float knob = r(28) * (1 + 0.08F * Math.max(hover, press));
			MenuStyle.box(c, left + tw * k - knob / 2, cy - knob / 2, knob, knob, knob / 2, 0xFFFFFFFF);
			MenuStyle.focusRing(c, left - r(4), y, tw + r(8), h, h / 2, focus);
		}
	}

	/** A colour: a swatch and its hex value; pressing it opens the colour picker. */
	static final class ColorSwatch extends Pressable {
		static final float H = 48;
		private final IntSupplier rgb;
		private final Label hex = new Label(Type.VALUE, "");

		ColorSwatch(IntSupplier rgb, Runnable open) {
			super(open);
			this.rgb = rgb;
		}

		@Override
		public float prefWidth() {
			return r(48) + r(14) + r(118);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", color " + hex.text() + ", button");
		}

		@Override
		protected void paint(Canvas c) {
			int value = rgb.getAsInt();
			hex.set(String.format(Locale.ROOT, "#%06X", value & 0xFFFFFF));
			float s = r(48);
			MenuStyle.framed(c, x, y, w, h, r(12), Palette.lerp(MenuStyle.FIELD, MenuStyle.SEGMENT_HOVER, hover), MenuStyle.FIELD_EDGE);
			MenuStyle.box(c, x + r(6), y + r(6), s - r(12), h - r(12), r(8), 0xFF000000 | value);
			MenuStyle.ring(c, x + r(6), y + r(6), s - r(12), h - r(12), r(8), MenuStyle.edge(), 0x40FFFFFF);
			MenuStyle.text(c, hex, x + s + r(4), y + h / 2, MenuStyle.ROW_TEXT);
			MenuStyle.focusRing(c, x, y, w, h, r(12), focus);
		}
	}

	/**
	 * A choice with too many options for a segmented control: the chosen option between two arrows. A click on the left
	 * half goes back, on the right half forward (wrapping round); the arrow keys too.
	 */
	static final class Stepper extends Pressable {
		static final float H = 52;
		private static final float W = 330;
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
		public boolean keyDown(KeyEvent event) {
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
			float rad = r(14);
			MenuStyle.box(c, x, y, w, h, rad, Palette.lerp(MenuStyle.SEGMENTS, MenuStyle.SEGMENT_HOVER, hover * 0.6F));
			float cy = y + h / 2;
			MenuStyle.icon(c, Icon.CHEVRON_LEFT, x + r(26), cy, r(24), MenuStyle.CHIP_TEXT);
			MenuStyle.icon(c, Icon.CHEVRON_RIGHT, x + w - r(26), cy, r(24), MenuStyle.CHIP_TEXT);
			int i = Math.max(0, selected.getAsInt());
			MenuStyle.centered(c, labels[i], x + w / 2, cy, MenuStyle.TAB_TEXT_SELECTED, w - r(100));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/**
	 * A key: its name in a field; pressing it waits for the next key or mouse button ("Press a key…"), Backspace or
	 * Delete clears it, Escape cancels. The value is Minecraft's version-neutral key name ({@code key.keyboard.c}).
	 */
	static final class KeyBind extends Pressable {
		static final float H = 52;
		private static final float W = 330;
		private final java.util.function.Supplier<String> value;
		private final java.util.function.Consumer<String> set;
		private final Label shown = new Label(Type.VALUE, "");
		private final Label waiting = new Label(Type.VALUE, "Press a key…");
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
		public boolean keyDown(KeyEvent event) {
			if (!listening) {
				return super.keyDown(event);
			}
			listening = false;
			int code = event.key();
			if (event.isEscape()) {
				return true;
			}
			if (code == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE || code == com.mojang.blaze3d.platform.InputConstants.KEY_DELETE) {
				set.accept("");
			} else {
				set.accept(com.mojang.blaze3d.platform.InputConstants.getKey(event).getName());
			}
			return true;
		}

		@Override
		public boolean captureMouseButton(int button) {
			if (!listening) {
				return false;
			}
			listening = false;
			set.accept(com.mojang.blaze3d.platform.InputConstants.Type.MOUSE.getOrCreate(button).getName());
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
			return withReason(accessibleName() + ", key, " + (listening ? "press a key" : shown.text()));
		}

		@Override
		protected void paint(Canvas c) {
			String key = value.get();
			if (!key.equals(shownFor)) {
				shownFor = key;
				shown.set(KeyNames.display(key));
			}
			float rad = r(12);
			int edge = listening ? MenuStyle.ACCENT : Palette.lerp(MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, hover);
			MenuStyle.framed(c, x, y, w, h, rad, MenuStyle.FIELD, edge);
			Label text = listening ? waiting : shown;
			MenuStyle.fitted(c, text, x + r(20), y + h / 2, listening ? MenuStyle.ACCENT : key.isEmpty() ? MenuStyle.LABEL : MenuStyle.ROW_TEXT,
				w - r(40));
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}

	/**
	 * A chosen thing (a sound, a list of items): its name in a field with a chevron; pressing it opens the list picker. The
	 * text comes from {@code shown} each frame (cheap: a label only changes when the text does).
	 */
	static final class PickField extends Pressable {
		private static final float W = 330;
		private final java.util.function.Supplier<String> shown;
		private final Label text = new Label(Type.VALUE, "");

		PickField(java.util.function.Supplier<String> shown, Runnable open) {
			super(open);
			this.shown = shown;
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
			float rad = r(12);
			MenuStyle.framed(c, x, y, w, h, rad, MenuStyle.FIELD, Palette.lerp(MenuStyle.FIELD_EDGE, MenuStyle.SEARCH_EDGE_HOVER, hover));
			MenuStyle.fitted(c, text, x + r(20), y + h / 2, MenuStyle.ROW_TEXT, w - r(70));
			MenuStyle.icon(c, Icon.CHEVRON_DOWN, x + w - r(28), y + h / 2, r(24), MenuStyle.CHIP_TEXT);
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
		static final float H = 64;
		private final Label label;
		private final @Nullable Icon icon;
		private Look look;
		private @Nullable BooleanSupplier active;
		private float on = -1;

		TextButton(String text, @Nullable Icon icon, Look look, Runnable action) {
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
			return label.width(c) + 2 * r(28) + (icon != null ? r(30) + r(12) : 0);
		}

		@Override
		public String narration() {
			return withReason(accessibleName() + ", button" + (active != null ? active.getAsBoolean() ? ", on" : ", off" : ""));
		}

		@Override
		protected void paint(Canvas c) {
			on = MenuControls.ease(on, look == Look.ACCENT || active != null && active.getAsBoolean());
			float rad = r(14);
			int idle = look == Look.DANGER ? Palette.withAlpha(MenuStyle.DANGER, 0.24F) : MenuStyle.CHIP;
			int idleHover = look == Look.DANGER ? Palette.withAlpha(MenuStyle.DANGER, 0.36F) : MenuStyle.CHIP_HOVER;
			int fill = Palette.lerp(Palette.lerp(idle, idleHover, hover), Palette.lerp(MenuStyle.ACCENT, MenuStyle.ACCENT_HOVER, hover), on);
			fill = Palette.lerp(fill, 0xFF000000, press * 0.18F);
			MenuStyle.box(c, x, y, w, h, rad, fill);
			int ink = look == Look.DANGER ? MenuStyle.DANGER_TEXT : Palette.lerp(Palette.lerp(MenuStyle.CHIP_TEXT, MenuStyle.TAB_TEXT_SELECTED, hover),
				MenuStyle.ON_ACCENT, on);
			float total = label.width(c) + (icon != null ? r(30) + r(12) : 0);
			float left = x + (w - total) / 2;
			if (icon != null) {
				MenuStyle.icon(c, icon, left + r(15), y + h / 2, r(30), ink);
				left += r(30) + r(12);
			}
			MenuStyle.text(c, label, left, y + h / 2, ink);
			MenuStyle.focusRing(c, x, y, w, h, rad, focus);
		}
	}
}
