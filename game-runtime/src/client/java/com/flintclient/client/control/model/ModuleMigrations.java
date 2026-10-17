package com.flintclient.client.control.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Settings that replaced older ones read the older ones when the file doesn't have them yet, so a layout saved by
 * an earlier Flint looks the same after the update. The layout schema stays 1: new settings simply default when
 * missing, and nothing here ever writes an old key back ({@link #retiredKeys} are removed when the module is next
 * saved). Applied by the layout reader to each module once its settings and style overrides are read. Pure.
 * <ul>
 * <li>CPS {@code showRight} → {@code buttons}: on is Both, off is Left.</li>
 * <li>Keystrokes {@code showMouse} and {@code showSpace} → {@code layout}: mouse and jump, mouse only, or keys only
 * (jump without the mouse buttons has no layout of its own: the player turned the mouse off, so keys only).</li>
 * <li>Direction {@code showAxis} and {@code showDegrees} → {@code format}: either on is Detailed (the old module always
 * showed the name and added these), both off is Name. Both stay as settings of the Detailed format.</li>
 * <li>FPS {@code labelPosition} → {@code reversed} and {@code showLabel}: Before is reversed, Hidden is the label
 * off. The style's text colour, panel and panel opacity overrides, which FPS no longer reads, become its own
 * {@code textColor}, {@code background} and {@code backgroundOpacity} when the file has none of those yet, so an
 * older FPS counter keeps its look.</li>
 * <li>The style's "Show label" off → {@code labelPosition} Hidden (Ping, CPS), {@code showLabel} off (FPS) or
 * {@code labels} off (Coordinates), when the file has no such setting yet. Those modules' own setting decides the label from then
 * on, so the override is dropped then (it leaves the file on the next save).</li>
 * </ul>
 */
public final class ModuleMigrations {
	private static final List<String> NONE = List.of();
	private static final List<String> CPS_RETIRED = List.of("showRight");
	private static final List<String> KEYSTROKES_RETIRED = List.of("showMouse", "showSpace");
	private static final List<String> FPS_RETIRED = List.of("labelPosition");
	private static final List<String> KILL_SOUNDS_RETIRED = List.of("sound", "volume");

	private ModuleMigrations() {
	}

	/** Reads the older settings {@code module}'s file had into the settings that replaced them; {@code settings} is the file's. */
	public static void apply(ModuleState module, @Nullable JsonObject settings) {
		switch (module.id()) {
			case ModuleId.CPS -> {
				Boolean right = bool(settings, "showRight");
				if (!has(settings, ModuleSettings.Cps.BUTTONS) && right != null) {
					module.set(ModuleSettings.Cps.BUTTONS, right ? CpsButtons.BOTH : CpsButtons.LEFT);
				}
			}
			case ModuleId.KEYSTROKES -> {
				Boolean mouse = bool(settings, "showMouse");
				Boolean jump = bool(settings, "showSpace");
				if (!has(settings, ModuleSettings.Keystrokes.LAYOUT) && (mouse != null || jump != null)) {
					module.set(ModuleSettings.Keystrokes.LAYOUT, KeystrokesLayout.of(mouse == null || mouse, jump == null || jump));
				}
			}
			case ModuleId.FPS -> fps(module, settings);
			case com.flintclient.client.control.model.mods.PlayerMods.KillSounds.ID -> killSounds(module, settings);
			case ModuleId.DIRECTION -> {
				Boolean axis = bool(settings, ModuleSettings.Direction.SHOW_AXIS.id());
				Boolean degrees = bool(settings, ModuleSettings.Direction.SHOW_DEGREES.id());
				if (!has(settings, ModuleSettings.Direction.FORMAT) && (axis != null || degrees != null)) {
					boolean detailed = axis == null || axis || degrees == null || degrees;
					module.set(ModuleSettings.Direction.FORMAT, detailed ? DirectionFormat.DETAILED : DirectionFormat.NAME);
				}
			}
			default -> {
			}
		}
		SettingDescriptor<?> label = ModuleSettings.labelSetting(module.descriptor());
		Boolean showLabel = module.appearance().showLabel();
		// FPS's older label position counts as its own label setting: it already decided the label, the switch didn't.
		boolean ownLabel = label != null
			&& (has(settings, label) || ModuleId.FPS.equals(module.id()) && string(settings, "labelPosition") != null);
		if (label == null || showLabel == null || ownLabel) {
			if (ownLabel && showLabel != null && ModuleId.FPS.equals(module.id())) {
				// FPS no longer reads the switch at all: it leaves the file on the next save.
				module.setAppearance(module.appearance().withShowLabel(null));
			}
			return;
		}
		if (!showLabel) {
			if (label instanceof BoolSetting own) {
				module.set(own, false);
			} else {
				module.set(ModuleSettings.labelPosition(module.descriptor()), LabelPosition.HIDDEN);
			}
		}
		// The module's own setting decides from now on; the override is dropped (and so leaves the file on the next save).
		module.setAppearance(module.appearance().withShowLabel(null));
	}

	/** Setting ids of {@code module} that newer settings replaced: never written, and removed from a file when it is saved. */
	public static List<String> retiredKeys(ModuleDescriptor module) {
		return switch (module.id()) {
			case ModuleId.CPS -> CPS_RETIRED;
			case ModuleId.KEYSTROKES -> KEYSTROKES_RETIRED;
			case ModuleId.FPS -> FPS_RETIRED;
			case com.flintclient.client.control.model.mods.PlayerMods.KillSounds.ID -> KILL_SOUNDS_RETIRED;
			default -> NONE;
		};
	}

	/**
	 * Kill Sounds' older single sound ({@code sound}: orb, bell, levelup or anvil) and {@code volume} played for every kill: they
	 * become every kind of kill's sound and volume, when the file has none of the newer settings yet.
	 */
	private static void killSounds(ModuleState module, @Nullable JsonObject settings) {
		String sound = string(settings, "sound");
		JsonElement volume = settings == null ? null : settings.get("volume");
		if (sound == null && volume == null || has(settings, com.flintclient.client.control.model.mods.PlayerMods.KillSounds.MELEE)) {
			return;
		}
		String id = switch (sound == null ? "orb" : sound) {
			case "bell" -> "minecraft:block.note_block.bell";
			case "levelup" -> "minecraft:entity.player.levelup";
			case "anvil" -> "minecraft:block.anvil.land";
			default -> "minecraft:entity.experience_orb.pickup";
		};
		IdSetting[] sounds = {com.flintclient.client.control.model.mods.PlayerMods.KillSounds.PLAYER,
			com.flintclient.client.control.model.mods.PlayerMods.KillSounds.MELEE, com.flintclient.client.control.model.mods.PlayerMods.KillSounds.BOW,
			com.flintclient.client.control.model.mods.PlayerMods.KillSounds.ROD,
			com.flintclient.client.control.model.mods.PlayerMods.KillSounds.THROWABLE};
		IntSetting[] volumes = {com.flintclient.client.control.model.mods.PlayerMods.KillSounds.PLAYER_VOLUME,
			com.flintclient.client.control.model.mods.PlayerMods.KillSounds.MELEE_VOLUME,
			com.flintclient.client.control.model.mods.PlayerMods.KillSounds.BOW_VOLUME,
			com.flintclient.client.control.model.mods.PlayerMods.KillSounds.ROD_VOLUME,
			com.flintclient.client.control.model.mods.PlayerMods.KillSounds.THROWABLE_VOLUME};
		for (IdSetting each : sounds) {
			module.set(each, id);
		}
		if (volume != null && volume.isJsonPrimitive() && volume.getAsJsonPrimitive().isNumber()) {
			for (IntSetting each : volumes) {
				module.set(each, (int) Math.round(volume.getAsDouble()));
			}
		}
	}

	/** FPS's older label position and style overrides, read into its own settings (see the class comment). */
	private static void fps(ModuleState fps, @Nullable JsonObject settings) {
		String position = string(settings, "labelPosition");
		if (position != null && !has(settings, ModuleSettings.Fps.REVERSED) && !has(settings, ModuleSettings.Fps.SHOW_LABEL)) {
			if (position.equals("before")) {
				fps.set(ModuleSettings.Fps.REVERSED, true);
			} else if (position.equals("hidden")) {
				fps.set(ModuleSettings.Fps.SHOW_LABEL, false);
			}
		}
		Appearance own = fps.appearance();
		HudColor color = own.textColor();
		if (color != null && !color.isDefault() && !has(settings, ModuleSettings.Fps.TEXT_COLOR)) {
			fps.set(ModuleSettings.Fps.TEXT_COLOR, color.argb() & 0xFFFFFF);
		}
		if (own.background() != null && !has(settings, ModuleSettings.Fps.BACKGROUND)) {
			fps.set(ModuleSettings.Fps.BACKGROUND, own.background());
		}
		if (own.backgroundOpacity() != null && !has(settings, ModuleSettings.Fps.BACKGROUND_OPACITY)) {
			fps.set(ModuleSettings.Fps.BACKGROUND_OPACITY, ModuleSettings.Fps.BACKGROUND_OPACITY.clamp(own.backgroundOpacity()));
		}
	}

	/** Whether the file has a usable value for {@code setting} (not one this version reads as the default for being unusable). */
	private static boolean has(@Nullable JsonObject settings, SettingDescriptor<?> setting) {
		if (settings == null) {
			return false;
		}
		JsonElement value = settings.get(setting.id());
		return value != null && usable(setting, value);
	}

	private static boolean usable(SettingDescriptor<?> setting, JsonElement value) {
		if (!value.isJsonPrimitive()) {
			return false;
		}
		if (setting instanceof ChoiceSetting<?> choice) {
			return value.getAsJsonPrimitive().isString() && choice.byName(value.getAsString()) != null;
		}
		if (setting instanceof BoolSetting) {
			return value.getAsJsonPrimitive().isBoolean();
		}
		if (setting instanceof RgbSetting) {
			return value.getAsJsonPrimitive().isString() && RgbSetting.parseHex(value.getAsString()) >= 0;
		}
		if (setting instanceof IdSetting id) {
			return value.getAsJsonPrimitive().isString() && id.valid(value.getAsString().strip());
		}
		return value.getAsJsonPrimitive().isNumber();
	}

	private static @Nullable String string(@Nullable JsonObject settings, String key) {
		if (settings == null) {
			return null;
		}
		JsonElement value = settings.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	private static @Nullable Boolean bool(@Nullable JsonObject settings, String key) {
		if (settings == null) {
			return null;
		}
		JsonElement value = settings.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : null;
	}
}
