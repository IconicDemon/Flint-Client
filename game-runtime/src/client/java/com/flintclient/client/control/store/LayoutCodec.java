package com.flintclient.client.control.store;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.LayoutNames;
import com.flintclient.client.control.model.ModuleMigrations;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.SettingDescriptor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Layout files (schema {@value #SCHEMA}), self-contained so a file can be exported and imported as is:
 * <pre>
 * {"schema":1,"id":"…","name":"…",
 *  "appearance":{"theme":"flint","textColor":"default","padding":3},
 *  "modules":{"fps":{"enabled":true,"settings":{"showLow":false},
 *             "hud":{"anchor":"TOP_LEFT","x":4.0,"y":4.0,"scale":1.0,"appearance":{ overrides }}}}}
 * </pre>
 * The shared {@code "background"}, {@code "backgroundOpacity"} and {@code "accentColor"} are written only once set:
 * without them the panel and the label colour follow the style (Flint 70 %, Classic 40 %, Minimal none), so choosing
 * a style never bakes in another style's look.
 *
 * <p>Settings that replaced older ones read the older keys when the new ones are missing ({@link ModuleMigrations});
 * the older keys are removed when the module is written, never written back.
 *
 * <p>Reading never fails: each missing or unusable field takes its default (numbers are clamped), so one bad value
 * costs only itself. Writing updates the given object in place, so keys this version doesn't know survive, and
 * rewrites a known field only when its value differs from what the file's value reads as, so a value this
 * version can't use (a style added by a newer Flint, say) stays until the player changes that field. Every
 * module is written, on or off, so a file says exactly what it shows.
 */
public final class LayoutCodec {
	public static final int SCHEMA = 1;

	private LayoutCodec() {
	}

	/** The layout in {@code root}, saved under file id {@code id} (the file name wins over any "id" inside). */
	public static Layout read(JsonObject root, String id) {
		String name = JsonFields.string(root, "name");
		String cleaned = name == null ? "" : LayoutNames.clean(name);
		if (cleaned.isEmpty()) {
			cleaned = id;
		} else if (cleaned.codePointCount(0, cleaned.length()) > LayoutNames.MAX_NAME) {
			cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, LayoutNames.MAX_NAME)).strip();
		}
		Layout layout = new Layout(id, cleaned);
		layout.setAppearance(readShared(JsonFields.object(root, "appearance")));
		JsonObject modules = JsonFields.object(root, "modules");
		for (ModuleState module : layout.modules()) {
			readModule(module, JsonFields.object(modules, module.id()));
		}
		return layout;
	}

	/** The layout's shared style: a missing or unusable theme, colour or padding is the default; the panel may stay unset. */
	private static Appearance readShared(@Nullable JsonObject json) {
		return new Appearance(
			Appearance.THEME.parse(field(json, Appearance.THEME)),
			Appearance.TEXT_COLOR.parse(field(json, Appearance.TEXT_COLOR)),
			JsonFields.bool(json, Appearance.BACKGROUND.id()),
			integer(json, Appearance.BACKGROUND_OPACITY.id()),
			Appearance.PADDING.parse(field(json, Appearance.PADDING)),
			null,
			json == null ? null : choice(json, Appearance.ACCENT_COLOR.id(), HudColor.class));
	}

	private static void readModule(ModuleState module, @Nullable JsonObject json) {
		if (json == null) {
			return;
		}
		module.setEnabled(Boolean.TRUE.equals(JsonFields.bool(json, "enabled")));
		JsonObject settings = JsonFields.object(json, "settings");
		for (SettingDescriptor<?> setting : module.descriptor().settings()) {
			module.setFromJson(setting, settings == null ? null : settings.get(setting.id()));
		}
		JsonObject hud = JsonFields.object(json, "hud");
		HudPlacement fallback = module.descriptor().defaultPlacement();
		module.setPlacement(new HudPlacement(anchor(hud, fallback.anchor()),
			JsonFields.number(hud, "x", fallback.x()),
			JsonFields.number(hud, "y", fallback.y()),
			JsonFields.number(hud, "scale", fallback.scale())));
		module.setAppearance(readOverrides(JsonFields.object(hud, "appearance")));
		ModuleMigrations.apply(module, settings);
	}

	/** Per-module overrides: a missing or unusable field means "follow the layout". */
	private static Appearance readOverrides(@Nullable JsonObject json) {
		if (json == null) {
			return Appearance.INHERIT;
		}
		return new Appearance(
			choice(json, Appearance.THEME.id(), HudThemeId.class),
			choice(json, Appearance.TEXT_COLOR.id(), HudColor.class),
			JsonFields.bool(json, Appearance.BACKGROUND.id()),
			integer(json, Appearance.BACKGROUND_OPACITY.id()),
			integer(json, Appearance.PADDING.id()),
			JsonFields.bool(json, Appearance.SHOW_LABEL.id()),
			choice(json, Appearance.ACCENT_COLOR.id(), HudColor.class));
	}

	/** A new object holding {@code layout} (for export, or a first save). */
	public static JsonObject toJson(Layout layout) {
		JsonObject root = new JsonObject();
		write(layout, root);
		return root;
	}

	/** Writes {@code layout} into {@code root}, updating known fields in place and leaving every other key. */
	public static void write(Layout layout, JsonObject root) {
		root.addProperty(JsonStore.SCHEMA_KEY, SCHEMA);
		root.addProperty("id", layout.id());
		root.addProperty("name", layout.name());
		JsonObject appearance = JsonFields.child(root, "appearance");
		writeAppearance(appearance, readShared(appearance), layout.appearance());
		JsonObject modules = JsonFields.child(root, "modules");
		for (ModuleState module : layout.modules()) {
			writeModule(module, JsonFields.child(modules, module.id()));
		}
	}

	private static void writeModule(ModuleState module, JsonObject json) {
		JsonFields.put(json, "enabled", Boolean.TRUE.equals(JsonFields.bool(json, "enabled")), module.enabled(),
			new JsonPrimitive(module.enabled()));
		JsonObject settings = JsonFields.child(json, "settings");
		List<SettingDescriptor<?>> descriptors = module.descriptor().settings();
		for (int i = 0; i < descriptors.size(); i++) {
			put(settings, descriptors.get(i), module.value(i));
		}
		for (String retired : ModuleMigrations.retiredKeys(module.descriptor())) {
			settings.remove(retired);
		}
		JsonObject hud = JsonFields.child(json, "hud");
		HudPlacement placement = module.placement();
		HudPlacement fallback = module.descriptor().defaultPlacement();
		JsonFields.put(hud, "anchor", anchor(hud, fallback.anchor()), placement.anchor(), new JsonPrimitive(placement.anchor().name()));
		JsonFields.put(hud, "x", JsonFields.number(hud, "x", fallback.x()), placement.x(), new JsonPrimitive(placement.x()));
		JsonFields.put(hud, "y", JsonFields.number(hud, "y", fallback.y()), placement.y(), new JsonPrimitive(placement.y()));
		// The scale is compared as read back (snapped to a step), so a hand-written 1 stays 1.
		float scaleOnFile = new HudPlacement(Anchor.TOP_LEFT, 0, 0, JsonFields.number(hud, "scale", fallback.scale())).scale();
		JsonFields.put(hud, "scale", scaleOnFile, placement.scale(), new JsonPrimitive(placement.scale()));
		JsonObject appearance = JsonFields.child(hud, "appearance");
		writeAppearance(appearance, readOverrides(appearance), module.appearance());
		if (appearance.size() == 0) {
			hud.remove("appearance");
		}
	}

	/** Writes each field of {@code value} (removing unset ones), keeping the file's own value where it reads the same. */
	private static void writeAppearance(JsonObject json, Appearance onFile, Appearance value) {
		JsonFields.put(json, Appearance.THEME.id(), onFile.theme(), value.theme(),
			value.theme() == null ? null : Appearance.THEME.toJson(value.theme()));
		JsonFields.put(json, Appearance.TEXT_COLOR.id(), onFile.textColor(), value.textColor(),
			value.textColor() == null ? null : Appearance.TEXT_COLOR.toJson(value.textColor()));
		JsonFields.put(json, Appearance.BACKGROUND.id(), onFile.background(), value.background(),
			value.background() == null ? null : Appearance.BACKGROUND.toJson(value.background()));
		JsonFields.put(json, Appearance.BACKGROUND_OPACITY.id(), onFile.backgroundOpacity(), value.backgroundOpacity(),
			value.backgroundOpacity() == null ? null : Appearance.BACKGROUND_OPACITY.toJson(value.backgroundOpacity()));
		JsonFields.put(json, Appearance.PADDING.id(), onFile.padding(), value.padding(),
			value.padding() == null ? null : Appearance.PADDING.toJson(value.padding()));
		JsonFields.put(json, Appearance.SHOW_LABEL.id(), onFile.showLabel(), value.showLabel(),
			value.showLabel() == null ? null : Appearance.SHOW_LABEL.toJson(value.showLabel()));
		JsonFields.put(json, Appearance.ACCENT_COLOR.id(), onFile.accentColor(), value.accentColor(),
			value.accentColor() == null ? null : Appearance.ACCENT_COLOR.toJson(value.accentColor()));
	}

	@SuppressWarnings("unchecked")
	private static <T> void put(JsonObject settings, SettingDescriptor<T> setting, Object value) {
		JsonFields.put(settings, setting.id(), setting, (T) value);
	}

	private static @Nullable JsonElement field(@Nullable JsonObject object, SettingDescriptor<?> setting) {
		return object == null ? null : object.get(setting.id());
	}

	/** The anchor named at {@code "anchor"} (any case), or {@code fallback}. */
	private static Anchor anchor(@Nullable JsonObject hud, Anchor fallback) {
		String name = JsonFields.string(hud, "anchor");
		if (name != null) {
			for (Anchor candidate : Anchor.values()) {
				if (candidate.name().equalsIgnoreCase(name)) {
					return candidate;
				}
			}
		}
		return fallback;
	}

	private static <E extends Enum<E>> @Nullable E choice(JsonObject json, String key, Class<E> type) {
		String name = JsonFields.string(json, key);
		if (name == null) {
			return null;
		}
		for (E option : type.getEnumConstants()) {
			if (option.name().equals(name.toUpperCase(Locale.ROOT))) {
				return option;
			}
		}
		return null;
	}

	/** A number for an appearance field (clamped later by {@link Appearance}), or null when missing or unusable. */
	private static @Nullable Integer integer(@Nullable JsonObject json, String key) {
		if (json == null) {
			return null;
		}
		JsonElement value = json.get(key);
		if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			double number = value.getAsDouble();
			if (Double.isFinite(number)) {
				return (int) Math.round(Math.clamp(number, Integer.MIN_VALUE, Integer.MAX_VALUE));
			}
		}
		return null;
	}
}
