package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One sound or item, by its namespaced id ({@code "minecraft:entity.experience_orb.pickup"}); empty means none. Ids are
 * the same on every Minecraft version that has the thing, so a profile reads alike everywhere; an id the running version
 * doesn't know is kept but does nothing (the version layer checks).
 */
public final class IdSetting extends SettingDescriptor<String> {
	public static final String NONE = "";
	static final Pattern ID = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
	private final IdKind kind;

	public IdSetting(String id, String label, @Nullable String description, IdKind kind, String defaultId) {
		super(id, label, description, defaultId);
		if (!defaultId.isEmpty() && !ID.matcher(defaultId).matches()) {
			throw new IllegalArgumentException("bad default id for " + id + ": " + defaultId);
		}
		this.kind = kind;
	}

	public IdKind kind() {
		return kind;
	}

	static boolean validId(String value) {
		return ID.matcher(value).matches();
	}

	@Override
	public Class<String> type() {
		return String.class;
	}

	@Override
	public String parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			String value = json.getAsString().strip();
			if (value.isEmpty() || validId(value)) {
				return value;
			}
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(String value) {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof String s && (s.isEmpty() || validId(s));
	}

	@Override
	public String clamp(String value) {
		return valid(value) ? value : defaultValue();
	}
}
