package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One of a fixed set of labelled options (a segmented control). Saved as the option's lower-case name, and
 * read back ignoring case, so {@code "PERCENT"} written by hand still works.
 */
public sealed class ChoiceSetting<E extends Enum<E> & Labelled> extends SettingDescriptor<E> permits ColorSetting {
	private final Class<E> type;
	private final List<E> options;

	public ChoiceSetting(String id, String label, @Nullable String description, Class<E> type, E defaultValue) {
		super(id, label, description, defaultValue);
		this.type = type;
		this.options = List.of(type.getEnumConstants());
	}

	/** Every option, in declaration order. */
	public final List<E> options() {
		return options;
	}

	@Override
	public final Class<E> type() {
		return type;
	}

	@Override
	public final E parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			E option = byName(json.getAsString());
			if (option != null) {
				return option;
			}
		}
		return defaultValue();
	}

	@Override
	public final JsonElement toJson(E value) {
		return new JsonPrimitive(key(value));
	}

	@Override
	public final boolean valid(@Nullable Object value) {
		return type.isInstance(value);
	}

	@Override
	public final E clamp(E value) {
		return value;
	}

	/** The option called {@code name} (any case), or null. */
	public final @Nullable E byName(String name) {
		for (E option : options) {
			if (option.name().equalsIgnoreCase(name)) {
				return option;
			}
		}
		return null;
	}

	/** How an option is written in files. */
	public static String key(Enum<?> option) {
		return option.name().toLowerCase(Locale.ROOT);
	}
}
