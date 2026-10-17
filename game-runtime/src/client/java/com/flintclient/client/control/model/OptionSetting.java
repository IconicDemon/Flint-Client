package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * One of a fixed list of named options (a segmented control, or a dropdown when there are many), for settings whose
 * options don't deserve an enum of their own. The value is the option's index; files store its key (lower case), so
 * reordering or adding options never changes what a saved file means. An unknown key reads as the default.
 */
public final class OptionSetting extends SettingDescriptor<Integer> {
	private final List<String> keys;
	private final List<String> labels;

	/** {@code options} alternate key and label: {@code "hold", "Hold", "toggle", "Toggle"}. */
	public OptionSetting(String id, String label, @Nullable String description, int defaultIndex, String... options) {
		super(id, label, description, defaultIndex);
		if (options.length < 4 || options.length % 2 != 0) {
			throw new IllegalArgumentException("options for " + id + " come in key/label pairs, at least two");
		}
		String[] k = new String[options.length / 2];
		String[] l = new String[options.length / 2];
		for (int i = 0; i < k.length; i++) {
			k[i] = options[2 * i].toLowerCase(Locale.ROOT);
			l[i] = options[2 * i + 1];
		}
		this.keys = List.of(k);
		this.labels = List.of(l);
		if (defaultIndex < 0 || defaultIndex >= k.length) {
			throw new IllegalArgumentException("bad default for " + id);
		}
	}

	public List<String> keys() {
		return keys;
	}

	public List<String> labels() {
		return labels;
	}

	public int size() {
		return keys.size();
	}

	/** The index of {@code key} (any case), or -1. */
	public int indexOf(String key) {
		return keys.indexOf(key.toLowerCase(Locale.ROOT));
	}

	@Override
	public Class<Integer> type() {
		return Integer.class;
	}

	@Override
	public Integer parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			int index = indexOf(json.getAsString().strip());
			if (index >= 0) {
				return index;
			}
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(Integer value) {
		return new JsonPrimitive(keys.get(clamp(value)));
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof Integer i && i >= 0 && i < keys.size();
	}

	@Override
	public Integer clamp(Integer value) {
		return value < 0 || value >= keys.size() ? defaultValue() : value;
	}
}
