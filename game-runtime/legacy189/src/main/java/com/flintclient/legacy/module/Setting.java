package com.flintclient.legacy.module;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.ChoiceSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.legacy.shared.KeyNames189;
import com.flintclient.legacy.shared.Slot;
import java.util.List;
import java.util.Locale;

/**
 * One setting as 1.8.9's menu shows it and its mods read it: a label, an optional description (the row's tooltip) and a value,
 * kept in Flint's shared settings ({@link Slot}): the same setting, id and default as on every Minecraft line. The kinds are
 * what the 1.8.9 menu has controls for (a switch, a slider, side-by-side buttons or a list, a colour, a key); each converts the
 * shared value to what its control shows (a colour and its opacity setting become one ARGB value, a key name a 1.8.9 key code).
 */
public abstract class Setting<T> {
	public final String id;
	/** The row's name: the Right Shift catalog's for this setting once the page is made, else the shared setting's. */
	public String label;
	public final String description;
	/** The shared setting it shows (for a colour, its colour). */
	public final SettingDescriptor<?> shared;
	protected final Slot slot;

	protected Setting(Slot slot, SettingDescriptor<?> shared) {
		this.slot = slot;
		this.shared = shared;
		this.id = shared.id();
		this.label = titleCase(shared.label());
		this.description = shared.description();
	}

	public abstract T get();

	public abstract void set(T next);

	public abstract T defaultValue();

	/** Grows with every change (of this setting or another of its module), so views made from the value are remade only then. */
	public int revision() {
		return slot.revision();
	}

	public boolean isDefault() {
		return get().equals(defaultValue());
	}

	public void reset() {
		set(defaultValue());
	}

	/** Whether the setting does anything now (with the others as they are), as on every Minecraft line. */
	public boolean applies() {
		return slot.reason() == null;
	}

	/** "Show “FPS”" → "Show “FPS”", "Text color" → "Text Color": the 1.8.9 menu's (and the catalog's) row style. */
	static String titleCase(String label) {
		StringBuilder b = new StringBuilder(label.length());
		boolean start = true;
		for (int i = 0; i < label.length(); i++) {
			char c = label.charAt(i);
			b.append(start ? Character.toUpperCase(c) : c);
			start = c == ' ';
		}
		return b.toString();
	}

	// ---------- kinds ----------

	public static final class Bool extends Setting<Boolean> {
		public Bool(Slot slot, BoolSetting shared) {
			super(slot, shared);
		}

		public boolean on() {
			return (Boolean) slot.get();
		}

		@Override
		public Boolean get() {
			return on();
		}

		@Override
		public void set(Boolean next) {
			slot.set(next);
		}

		@Override
		public Boolean defaultValue() {
			return (Boolean) slot.defaultValue();
		}
	}

	/** A whole number from min to max in steps, shown with its unit; a slider. */
	public static class Int extends Setting<Integer> {
		public final int min;
		public final int max;
		public final int step;
		public final String unit;

		public Int(Slot slot, IntSetting shared) {
			this(slot, shared, shared.min(), shared.max());
		}

		/** With a narrower range than the shared setting's (what 1.8.9 can show well); values outside it read as its ends. */
		public Int(Slot slot, IntSetting shared, int min, int max) {
			this(slot, shared, min, max, shared.step(), shared.unit());
		}

		protected Int(Slot slot, SettingDescriptor<?> shared, int min, int max, int step, String unit) {
			super(slot, shared);
			this.min = min;
			this.max = max;
			this.step = step;
			this.unit = unit;
		}

		public int value() {
			return Math.max(min, Math.min(max, (Integer) slot.get()));
		}

		@Override
		public Integer get() {
			return value();
		}

		@Override
		public void set(Integer next) {
			slot.set(snap(next));
		}

		protected int snap(int next) {
			return Math.max(min, Math.min(max, min + Math.round((next - min) / (float) step) * step));
		}

		@Override
		public Integer defaultValue() {
			return (Integer) slot.defaultValue();
		}

		/** The value as the slider's box shows it. */
		public String format(int v) {
			return v + unit;
		}
	}

	/**
	 * A decimal setting on the slider, in hundredths (or {@code factor} parts) of its unit: {@code value()} is the shared value times
	 * {@code factor}, rounded.
	 */
	public static final class Decimal extends Int {
		private final int factor;
		private final String shownUnit;

		public Decimal(Slot slot, FloatSetting shared, int factor, String shownUnit) {
			this(slot, shared, factor, shownUnit, shared.min(), shared.max());
		}

		/** With a narrower range than the shared setting's. */
		public Decimal(Slot slot, FloatSetting shared, int factor, String shownUnit, float min, float max) {
			super(slot, shared, Math.round(min * factor), Math.round(max * factor), Math.max(1, Math.round(shared.step() * factor)), shownUnit);
			this.factor = factor;
			this.shownUnit = shownUnit;
		}

		public float decimal() {
			return (Float) slot.get();
		}

		@Override
		public int value() {
			return Math.max(min, Math.min(max, Math.round(decimal() * factor)));
		}

		@Override
		public void set(Integer next) {
			slot.set(snap(next) / (float) factor);
		}

		@Override
		public Integer defaultValue() {
			return Math.round((Float) slot.defaultValue() * factor);
		}

		/** In percent when the unit shown is "%" (1.15 reads 115%), else the decimal with its unit. */
		@Override
		public String format(int v) {
			if (shownUnit.equals("%") && factor == 100) {
				return v + "%";
			}
			String text = factor == 1 ? Integer.toString(v) : String.format(Locale.ROOT, factor >= 100 ? "%.2f" : "%.1f", v / (float) factor);
			return text + shownUnit;
		}
	}

	/** One of a few named options (a shared choice or option setting); side-by-side buttons, or a list when there are many. */
	public static final class Choice extends Setting<Integer> {
		public final String[] options;
		/** Each option's key in files (the shared setting's). */
		private final String[] keys;
		private final List<?> values;

		/** A choice of the shared setting's own options (its enum's). */
		public Choice(Slot slot, ChoiceSetting<?> shared) {
			super(slot, shared);
			values = shared.options();
			options = new String[values.size()];
			keys = new String[values.size()];
			for (int i = 0; i < options.length; i++) {
				options[i] = ((com.flintclient.client.control.model.Labelled) values.get(i)).label();
				keys[i] = ChoiceSetting.key((Enum<?>) values.get(i));
			}
		}

		/** A choice of an option setting's options (stored as their index). */
		public Choice(Slot slot, OptionSetting shared) {
			super(slot, shared);
			values = null;
			options = shared.labels().toArray(new String[0]);
			keys = shared.keys().toArray(new String[0]);
		}

		/** The chosen option's key in files. */
		public String key() {
			return keys[Math.max(0, Math.min(keys.length - 1, index()))];
		}

		public boolean is(String key) {
			return key().equals(key);
		}

		public int index() {
			Object v = slot.get();
			return values == null ? (Integer) v : values.indexOf(v);
		}

		/** The chosen enum constant (a choice setting's). */
		@SuppressWarnings("unchecked")
		public <E> E value() {
			return (E) slot.get();
		}

		@Override
		public Integer get() {
			return index();
		}

		@Override
		public void set(Integer next) {
			int i = Math.max(0, Math.min(options.length - 1, next));
			slot.set(values == null ? Integer.valueOf(i) : values.get(i));
		}

		@Override
		public Integer defaultValue() {
			Object v = slot.defaultValue();
			return values == null ? (Integer) v : values.indexOf(v);
		}
	}

	/**
	 * A colour (ARGB) from a shared colour ({@code 0xRRGGBB}) and, when it has one, its opacity setting: a percentage, or alpha out
	 * of 255. A swatch and its hex value; with an opacity, the picker shows its slider too.
	 */
	public static final class Color extends Setting<Integer> {
		public final boolean alpha;
		private final Slot opacity;
		private final boolean percent;

		public Color(Slot slot, RgbSetting shared) {
			this(slot, shared, null, false);
		}

		/** With its opacity in {@code opacity}: a percentage when {@code percent}, else alpha 0..255. */
		public Color(Slot slot, RgbSetting shared, Slot opacity, boolean percent) {
			super(slot, shared);
			this.opacity = opacity;
			this.percent = percent;
			this.alpha = opacity != null;
		}

		private int alphaOf(Object v) {
			if (opacity == null) {
				return 255;
			}
			int a = (Integer) v;
			return percent ? Math.round(a * 255 / 100F) : a;
		}

		public int argb() {
			return alphaOf(opacity == null ? null : opacity.get()) << 24 | (Integer) slot.get() & 0xFFFFFF;
		}

		/** The colour without its opacity. */
		public int rgb() {
			return (Integer) slot.get() & 0xFFFFFF;
		}

		@Override
		public Integer get() {
			return argb();
		}

		@Override
		public void set(Integer next) {
			slot.set(next & 0xFFFFFF);
			if (opacity != null) {
				int a = next >>> 24;
				// The shared setting snaps it to its own step.
				opacity.set(percent ? Math.round(a * 100 / 255F) : a);
			}
		}

		@Override
		public int revision() {
			return slot.revision() + (opacity == null ? 0 : opacity.revision());
		}

		@Override
		public Integer defaultValue() {
			return alphaOf(opacity == null ? null : opacity.defaultValue()) << 24 | (Integer) slot.defaultValue() & 0xFFFFFF;
		}
	}

	/** A key or mouse button: 1.8.9's key code (mouse buttons are -100 + button; 0 for none), stored by its shared key name. */
	public static class Key extends Setting<Integer> {
		public Key(Slot slot, KeySetting shared) {
			super(slot, shared);
		}

		public int code() {
			return KeyNames189.code((String) slot.get());
		}

		@Override
		public Integer get() {
			return code();
		}

		@Override
		public void set(Integer next) {
			slot.set(KeyNames189.name(next));
		}

		@Override
		public Integer defaultValue() {
			return KeyNames189.code((String) slot.defaultValue());
		}
	}
}
