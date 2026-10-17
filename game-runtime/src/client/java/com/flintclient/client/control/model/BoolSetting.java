package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

/** An on/off setting (a toggle row). */
public final class BoolSetting extends SettingDescriptor<Boolean> {
	public BoolSetting(String id, String label, boolean defaultValue) {
		this(id, label, null, defaultValue);
	}

	public BoolSetting(String id, String label, @Nullable String description, boolean defaultValue) {
		super(id, label, description, defaultValue);
	}

	@Override
	public Class<Boolean> type() {
		return Boolean.class;
	}

	@Override
	public Boolean parse(@Nullable JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) {
			return json.getAsBoolean();
		}
		return defaultValue();
	}

	@Override
	public JsonElement toJson(Boolean value) {
		return new JsonPrimitive(value);
	}

	@Override
	public boolean valid(@Nullable Object value) {
		return value instanceof Boolean;
	}

	@Override
	public Boolean clamp(Boolean value) {
		return value;
	}
}
