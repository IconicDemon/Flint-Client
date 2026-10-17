package com.flintclient.client.control.model;

import com.flintclient.client.control.hud.HudPlacement;
import com.google.gson.JsonElement;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One module's state inside a {@link Layout}: on or off, its setting values, where it sits and its appearance
 * overrides. Every value is kept valid: setters clamp, so a renderer can trust what it reads. Reading is
 * allocation-free (values are stored boxed once, when set). Each real change bumps {@link #revision()}, which
 * renderers compare to know when to rebuild cached text, and stores compare to know what needs saving.
 */
public final class ModuleState {
	private final ModuleDescriptor descriptor;
	private final Object[] values;
	private boolean enabled;
	private HudPlacement placement;
	private Appearance appearance = Appearance.INHERIT;
	private int revision;

	/** Off, with every default. */
	public ModuleState(ModuleDescriptor descriptor) {
		this.descriptor = descriptor;
		this.values = new Object[descriptor.settings().size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = descriptor.settings().get(i).defaultValue();
		}
		this.placement = descriptor.defaultPlacement();
	}

	public ModuleDescriptor descriptor() {
		return descriptor;
	}

	public String id() {
		return descriptor.id();
	}

	public boolean enabled() {
		return enabled;
	}

	/** Returns whether this changed anything. */
	public boolean setEnabled(boolean value) {
		if (enabled == value) {
			return false;
		}
		enabled = value;
		revision++;
		return true;
	}

	public <T> T get(SettingDescriptor<T> setting) {
		return setting.type().cast(values[index(setting)]);
	}

	public boolean bool(BoolSetting setting) {
		return (Boolean) values[index(setting)];
	}

	public int integer(IntSetting setting) {
		return (Integer) values[index(setting)];
	}

	/** An {@link RgbSetting}'s value, {@code 0xRRGGBB}. */
	public int rgb(RgbSetting setting) {
		return get(setting);
	}

	/** An {@link OptionSetting}'s value: the index of the chosen option. */
	public int option(OptionSetting setting) {
		return (Integer) values[index(setting)];
	}

	/** A {@link TextSetting}'s or {@link KeySetting}'s value. */
	public String text(SettingDescriptor<String> setting) {
		return (String) values[index(setting)];
	}

	/** An {@link IdValuesSetting}'s value: the ids set away from their default, and their values. */
	@SuppressWarnings("unchecked")
	public java.util.Map<String, Integer> values(IdValuesSetting setting) {
		return (java.util.Map<String, Integer>) values[index(setting)];
	}

	/** An {@link IdListSetting}'s value: its ids, sorted. */
	@SuppressWarnings("unchecked")
	public java.util.List<String> ids(IdListSetting setting) {
		return (java.util.List<String>) values[index(setting)];
	}

	public float decimal(FloatSetting setting) {
		return (Float) values[index(setting)];
	}

	public <E extends Enum<E> & Labelled> E choice(ChoiceSetting<E> setting) {
		return setting.type().cast(values[index(setting)]);
	}

	/** The value of the setting at {@code index} in the descriptor's {@link ModuleDescriptor#settings()}. */
	public Object value(int index) {
		return values[index];
	}

	/** Sets a value, clamped to the setting's rules. Returns whether this changed anything. */
	public <T> boolean set(SettingDescriptor<T> setting, T value) {
		return store(index(setting), setting.clamp(Objects.requireNonNull(value)));
	}

	/** Sets the value read from a file (unusable values become the default). Returns whether this changed anything. */
	public boolean setFromJson(SettingDescriptor<?> setting, @Nullable JsonElement json) {
		return store(index(setting), setting.parse(json));
	}

	public HudPlacement placement() {
		return placement;
	}

	public boolean setPlacement(HudPlacement value) {
		if (placement.equals(Objects.requireNonNull(value))) {
			return false;
		}
		placement = value;
		revision++;
		return true;
	}

	/** This module's overrides; null fields follow the layout. */
	public Appearance appearance() {
		return appearance;
	}

	public boolean setAppearance(Appearance value) {
		if (appearance.equals(Objects.requireNonNull(value))) {
			return false;
		}
		appearance = value;
		revision++;
		return true;
	}

	/** Every setting and appearance override back to its default. The position and the on/off state stay. */
	public boolean reset() {
		boolean changed = setAppearance(Appearance.INHERIT);
		for (int i = 0; i < values.length; i++) {
			changed |= store(i, descriptor.settings().get(i).defaultValue());
		}
		return changed;
	}

	/** Whether {@code setting} holds its default value. */
	public boolean isDefault(SettingDescriptor<?> setting) {
		return Objects.equals(values[index(setting)], setting.defaultValue());
	}

	/** One setting back to its default. Returns whether this changed anything. */
	public boolean resetSetting(SettingDescriptor<?> setting) {
		return store(index(setting), setting.defaultValue());
	}

	/** Back to the module's default position and size. */
	public boolean resetPlacement() {
		return setPlacement(descriptor.defaultPlacement());
	}

	/** Grows by one with every real change; never goes back. */
	public int revision() {
		return revision;
	}

	/** Makes this identical to {@code other} (same module), counting it as one change when anything differs. */
	public boolean copyFrom(ModuleState other) {
		if (other.descriptor != descriptor) {
			throw new IllegalArgumentException("different modules: " + id() + ", " + other.id());
		}
		if (sameState(other)) {
			return false;
		}
		System.arraycopy(other.values, 0, values, 0, values.length);
		enabled = other.enabled;
		placement = other.placement;
		appearance = other.appearance;
		revision++;
		return true;
	}

	public ModuleState copy() {
		ModuleState copy = new ModuleState(descriptor);
		copy.copyFrom(this);
		return copy;
	}

	private boolean store(int index, Object value) {
		if (values[index].equals(value)) {
			return false;
		}
		values[index] = value;
		revision++;
		return true;
	}

	private int index(SettingDescriptor<?> setting) {
		int index = descriptor.indexOf(setting);
		if (index < 0) {
			throw new IllegalArgumentException(setting + " is not a setting of " + id());
		}
		return index;
	}

	private boolean sameState(ModuleState other) {
		return enabled == other.enabled && placement.equals(other.placement) && appearance.equals(other.appearance)
			&& Arrays.equals(values, other.values);
	}

	/** Equal when the module and all of its state match (the revision is not part of it). */
	@Override
	public boolean equals(Object o) {
		return o instanceof ModuleState other && other.descriptor == descriptor && sameState(other);
	}

	@Override
	public int hashCode() {
		return Objects.hash(descriptor.id(), enabled, placement, appearance, Arrays.hashCode(values));
	}

	@Override
	public String toString() {
		return "ModuleState[" + id() + (enabled ? " on " : " off ") + placement + " " + appearance + " " + Arrays.toString(values) + "]";
	}
}
