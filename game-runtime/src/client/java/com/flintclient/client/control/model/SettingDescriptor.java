package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One typed, player-facing setting: what it is called, its default and the rules for a valid value. Values
 * read from a file go through {@link #parse}, which never fails: a missing or unusable value means the
 * default, and a number out of range is clamped, so one bad field never costs the rest of a file.
 */
public abstract sealed class SettingDescriptor<T> permits BoolSetting, IntSetting, FloatSetting, ChoiceSetting, RgbSetting, OptionSetting, KeySetting,
	TextSetting, IdSetting, IdValuesSetting, IdListSetting {
	private final String id;
	private final String label;
	private final @Nullable String description;
	private final T defaultValue;
	/** Set once, while the constant is made ({@link #explained()}); never changes after class initialisation. */
	private boolean explain;

	SettingDescriptor(String id, String label, @Nullable String description, T defaultValue) {
		this.id = Objects.requireNonNull(id);
		this.label = Objects.requireNonNull(label);
		this.description = description;
		this.defaultValue = Objects.requireNonNull(defaultValue);
	}

	/** The key in files; stable once released. */
	public final String id() {
		return id;
	}

	public final String label() {
		return label;
	}

	/** A short explanation under the label, or null when the label says it all. */
	public final @Nullable String description() {
		return description;
	}

	public final T defaultValue() {
		return defaultValue;
	}

	/**
	 * Whether the description is needed to use the setting well (how a value is measured, a delay before it
	 * shows), so a settings panel shows it as a line under the label rather than only as a tooltip.
	 */
	public final boolean explain() {
		return explain && description != null;
	}

	/** Marks the description as one to show under the label ({@link #explain()}); for the constants, once, as they are made. */
	@SuppressWarnings("unchecked")
	final <S extends SettingDescriptor<T>> S explained() {
		explain = true;
		return (S) this;
	}

	/** The Java type of the values. */
	public abstract Class<T> type();

	/** The value in {@code json}, clamped when it is a number out of range, or the default when unusable. */
	public abstract T parse(@Nullable JsonElement json);

	public abstract JsonElement toJson(T value);

	/** Whether {@code value} is of the right type and already within range (and on the step). */
	public abstract boolean valid(@Nullable Object value);

	/** The nearest valid value. */
	public abstract T clamp(T value);

	/** Any object as a valid value: clamped when it has the right type, otherwise the default. */
	public final T coerce(@Nullable Object value) {
		Class<T> type = type();
		return type.isInstance(value) ? clamp(type.cast(value)) : defaultValue;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "[" + id + "]";
	}
}
