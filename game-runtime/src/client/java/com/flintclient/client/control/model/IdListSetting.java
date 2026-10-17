package com.flintclient.client.control.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.List;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * A set of ids (the items Inventory protects from dropping), stored as a JSON array in id order, each id once. An
 * unusable entry is dropped on its own; the value is an unmodifiable sorted list.
 */
public final class IdListSetting extends SettingDescriptor<List<String>> {
	public static final int MAX_ENTRIES = 1024;
	private final IdKind kind;

	public IdListSetting(String id, String label, @Nullable String description, IdKind kind, List<String> defaultIds) {
		super(id, label, description, List.copyOf(new TreeSet<>(defaultIds)));
		this.kind = kind;
	}

	public IdKind kind() {
		return kind;
	}

	/** {@code ids} with {@code id} in it ({@code on}) or not. */
	public List<String> with(List<String> ids, String id, boolean on) {
		TreeSet<String> next = new TreeSet<>(ids);
		if (on) {
			next.add(id);
		} else {
			next.remove(id);
		}
		return clamp(List.copyOf(next));
	}

	@Override
	@SuppressWarnings("unchecked")
	public Class<List<String>> type() {
		return (Class<List<String>>) (Class<?>) List.class;
	}

	@Override
	public List<String> parse(@Nullable JsonElement json) {
		if (json == null || !json.isJsonArray()) {
			return defaultValue();
		}
		TreeSet<String> out = new TreeSet<>();
		for (JsonElement e : json.getAsJsonArray()) {
			if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && IdSetting.validId(e.getAsString().strip())) {
				out.add(e.getAsString().strip());
			}
			if (out.size() >= MAX_ENTRIES) {
				break;
			}
		}
		return List.copyOf(out);
	}

	@Override
	public JsonElement toJson(List<String> value) {
		JsonArray out = new JsonArray();
		for (String id : new TreeSet<>(value)) {
			out.add(id);
		}
		return out;
	}

	@Override
	public boolean valid(@Nullable Object value) {
		if (!(value instanceof List<?> list) || list.size() > MAX_ENTRIES) {
			return false;
		}
		String last = null;
		for (Object o : list) {
			if (!(o instanceof String s) || !IdSetting.validId(s) || last != null && last.compareTo(s) >= 0) {
				return false;
			}
			last = s;
		}
		return true;
	}

	@Override
	public List<String> clamp(List<String> value) {
		TreeSet<String> out = new TreeSet<>();
		for (String s : value) {
			if (s != null && IdSetting.validId(s)) {
				out.add(s);
			}
			if (out.size() >= MAX_ENTRIES) {
				break;
			}
		}
		return List.copyOf(out);
	}
}
