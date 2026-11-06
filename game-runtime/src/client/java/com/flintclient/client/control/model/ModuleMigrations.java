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
 * <li>Shulker Preview {@code show} Holding Shift → {@code holdKey} on (the preview key starts as Shift).</li>
 * <li>Effects without {@code view} (from before its Tiles layout): still at the list's old default spot (left edge, 4, 0), it
 * moves to the new default, Minecraft's top-right corner; anywhere else it stays put.</li>
 * <li>Effects without {@code tileLevel} and with {@code tileTime} Below (the old default): the time moves onto the tile, where the
 * level now sits too.</li>
 * <li>Hearts {@code sameBarAbsorption} (absorption over your hearts, on by default) is retired, not read: golden hearts get their own
 * row above, as Minecraft draws them, until {@code sameRowAbsorption} is turned on.</li>
 * <li>Inventory {@code search} (the search bar, on by default) is retired, not read: its {@code searchBar} starts off.</li>
 * <li>Motion Blur {@code blurAlgorithm} {@code classic} (Flint's own trail, its default for a day before Natural Motion Blur's
 * algorithms became the whole list) needs nothing here: an option key the list no longer has reads as the default, Velocity
 * based, Natural Motion Blur's recommended one. Every other Motion Blur setting kept its id and meaning; the two new ones
 * ({@code quality}, {@code movementBlur}) start at their defaults.</li>
 * <li>Wavy Capes {@code capeMovement} → {@code movement}, whose new default is Realistic: the old default, Simulation 3D, becomes
 * Realistic (a file can't tell it from a choice); Vanilla, Simulation and Dungeons, which were chosen, stay.</li>
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
	private static final List<String> SHULKER_RETIRED = List.of("show");
	private static final List<String> TITLES_RETIRED = List.of("show");
	private static final List<String> TAB_RETIRED = List.of("pingColor");
	/** Inventory's search bar was {@code search}, on by default; it is {@code searchBar} now, off until chosen. */
	private static final List<String> INVENTORY_RETIRED = List.of("search");
	/** Hearts drew absorption over your hearts by default ({@code sameBarAbsorption}); {@code sameRowAbsorption} starts off. */
	private static final List<String> HEARTS_RETIRED = List.of("sameBarAbsorption");
	/** Particle Changer's swirl filter once hid every swirl by default (0%); it now starts at 100%. */
	private static final List<String> PARTICLES_RETIRED = List.of("effectParticles");
	/** Drop Prevention's scroll resistance once started at 2 notches, which felt like a delay leaving a protected slot. */
	private static final List<String> DROP_RETIRED = List.of("scrollResistance");
	/** Custom F3 before BetterF3: a switch and a colour per line group, an ease-in, the profiler chart at first and the help lines. */
	private static final List<String> F3_RETIRED = List.of("showMinecraftVersion", "showFPS", "showServer", "showSection",
		"showEntityCount", "showChunkSource",
		"showDimension", "showPos", "showBlockPos", "showChunkPos", "showFacing", "showClientLight", "showChunkInfo",
		"showBiome", "showDifficulty", "showSpawnState", "showShader", "showSounds", "showMemoryUsage", "showAllocation",
		"showCPU", "showDisplay", "showRenderer", "showGL", "showBlockHitResult", "showEntityHitResult",
		"colorMinecraftVersion", "colorFPS", "colorServer", "colorSection", "colorEntityCount", "colorChunkSource",
		"colorDimension", "colorPos", "colorBlockPos", "colorChunkPos", "colorFacing", "colorClientLight",
		"colorChunkInfo", "colorBiome", "colorDifficulty", "colorSpawnState", "colorShader", "colorSounds",
		"colorMemoryUsage", "colorAllocation", "colorCPU", "colorDisplay", "colorRenderer", "colorGL",
		"colorBlockHitResult", "colorEntityHitResult", "animate", "animateAlpha", "animatePos", "animationDuration",
		"showPieByDefault", "showHelpTips");
	private static final List<String> TOASTS_RETIRED = List.of("enabled", "advancement", "recipe", "system", "tutorial");
	/** Wavy Capes' movement was {@code capeMovement} (Simulation 3D by default); it is {@code movement} now, Realistic by default. */
	private static final List<String> WAVY_CAPES_RETIRED = List.of("capeMovement");

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
			case ModuleId.EFFECTS -> effects(module, settings);
			case com.flintclient.client.control.model.mods.PlayerMods.KillSounds.ID -> killSounds(module, settings);
			case com.flintclient.client.control.model.mods.UtilityMods.ToastControl.ID -> toasts(module, settings);
			case com.flintclient.client.control.model.mods.ToolMods.CustomF3.ID -> customF3(module, settings);
			case com.flintclient.client.control.model.mods.UtilityMods.Titles.ID -> {
				// Titles {@code show} (off hid them) is now Title Tweaks' Disable titles.
				Boolean shown = bool(settings, "show");
				if (shown != null && !has(settings, com.flintclient.client.control.model.mods.UtilityMods.Titles.DISABLE)) {
					module.set(com.flintclient.client.control.model.mods.UtilityMods.Titles.DISABLE, !shown);
				}
			}
			case com.flintclient.client.control.model.mods.VisualMods.WavyCapes.ID -> wavyCapes(module, settings);
			case com.flintclient.client.control.model.mods.UtilityMods.ShulkerPreview.ID -> {
				// Shulker Preview {@code show}: Holding Shift is now the hold switch (the key stays Shift).
				if ("shift".equals(string(settings, "show")) && !has(settings, com.flintclient.client.control.model.mods.UtilityMods.ShulkerPreview.HOLD)) {
					module.set(com.flintclient.client.control.model.mods.UtilityMods.ShulkerPreview.HOLD, true);
				}
			}
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
	/** Where Effects sat by default while its only look was the list: the middle of the left edge. */
	static final com.flintclient.client.control.hud.HudPlacement EFFECTS_OLD_DEFAULT = com.flintclient.client.control.hud.HudPlacement.of(
		com.flintclient.client.control.hud.Anchor.LEFT, 4, 0);

	/**
	 * Effects before its Tiles layout (the file has no {@code view} yet): a list never moved from its old default spot moves to the
	 * new default, the top-right corner where Minecraft draws its effect tiles, keeping its size. A list the player placed
	 * elsewhere stays exactly where it is.
	 */
	private static void effects(ModuleState module, @Nullable JsonObject settings) {
		if (settings != null && !has(settings, ModuleSettings.Effects.TILE_LEVEL) && "below".equals(string(settings, ModuleSettings.Effects.TILE_TIME.id()))) {
			// From before the level on the tile, when the time sat under it by default: the time moves onto the tile too.
			module.set(ModuleSettings.Effects.TILE_TIME, 1);
		}
		if (has(settings, ModuleSettings.Effects.VIEW)) {
			return;
		}
		com.flintclient.client.control.hud.HudPlacement placement = module.placement();
		if (placement.anchor() == EFFECTS_OLD_DEFAULT.anchor() && placement.x() == EFFECTS_OLD_DEFAULT.x()
			&& placement.y() == EFFECTS_OLD_DEFAULT.y()) {
			com.flintclient.client.control.hud.HudPlacement fresh = module.descriptor().defaultPlacement();
			module.setPlacement(new com.flintclient.client.control.hud.HudPlacement(fresh.anchor(), fresh.x(), fresh.y(), placement.scale()));
		}
	}

	public static List<String> retiredKeys(ModuleDescriptor module) {
		return switch (module.id()) {
			case ModuleId.CPS -> CPS_RETIRED;
			case ModuleId.KEYSTROKES -> KEYSTROKES_RETIRED;
			case ModuleId.FPS -> FPS_RETIRED;
			case com.flintclient.client.control.model.mods.PlayerMods.KillSounds.ID -> KILL_SOUNDS_RETIRED;
			case com.flintclient.client.control.model.mods.UtilityMods.ShulkerPreview.ID -> SHULKER_RETIRED;
			case com.flintclient.client.control.model.mods.UtilityMods.ToastControl.ID -> TOASTS_RETIRED;
			case com.flintclient.client.control.model.mods.ToolMods.CustomF3.ID -> F3_RETIRED;
			case com.flintclient.client.control.model.mods.UtilityMods.Titles.ID -> TITLES_RETIRED;
			case com.flintclient.client.control.model.mods.UtilityMods.TabEditor.ID -> TAB_RETIRED;
			case com.flintclient.client.control.model.mods.UtilityMods.Inventory.ID -> INVENTORY_RETIRED;
			case com.flintclient.client.control.model.mods.LookMods.Hearts.ID -> HEARTS_RETIRED;
			case com.flintclient.client.control.model.mods.VisualMods.ParticleChanger.ID -> PARTICLES_RETIRED;
			case com.flintclient.client.control.model.mods.ToolMods.DropPrevention.ID -> DROP_RETIRED;
			case com.flintclient.client.control.model.mods.VisualMods.WavyCapes.ID -> WAVY_CAPES_RETIRED;
			default -> NONE;
		};
	}

	/**
	 * Wavy Capes' older {@code capeMovement}: a movement other than its default (Simulation 3D) was chosen and stays; the default
	 * becomes the new one, Realistic. Only when the file has no {@code movement} yet.
	 */
	private static void wavyCapes(ModuleState module, @Nullable JsonObject settings) {
		OptionSetting movement = com.flintclient.client.control.model.mods.VisualMods.WavyCapes.MOVEMENT;
		String old = string(settings, "capeMovement");
		if (old == null || has(settings, movement)) {
			return;
		}
		int chosen = switch (old) {
			case "vanilla" -> com.flintclient.client.control.model.mods.VisualMods.WavyCapes.VANILLA;
			case "basic_simulation" -> com.flintclient.client.control.model.mods.VisualMods.WavyCapes.SIMULATION;
			case "dungeons" -> com.flintclient.client.control.model.mods.VisualMods.WavyCapes.DUNGEONS;
			default -> com.flintclient.client.control.model.mods.VisualMods.WavyCapes.REALISTIC;
		};
		module.set(movement, chosen);
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

	/**
	 * Custom F3 before BetterF3: its ease-in ({@code animate}) is now the slide-in animation, the profiler chart at first
	 * ({@code showPieByDefault}) is Always enable the profiler, and the help lines ({@code showHelpTips}) are the Help module.
	 */
	private static void customF3(ModuleState module, @Nullable JsonObject settings) {
		Boolean animate = bool(settings, "animate");
		if (animate != null && !has(settings, com.flintclient.client.control.model.mods.ToolMods.CustomF3.ANIMATIONS)) {
			module.set(com.flintclient.client.control.model.mods.ToolMods.CustomF3.ANIMATIONS, animate);
		}
		Boolean pie = bool(settings, "showPieByDefault");
		if (pie != null && !has(settings, com.flintclient.client.control.model.mods.ToolMods.CustomF3.ALWAYS_PROFILER)) {
			module.set(com.flintclient.client.control.model.mods.ToolMods.CustomF3.ALWAYS_PROFILER, pie);
		}
		Boolean help = bool(settings, "showHelpTips");
		if (help != null && !has(settings, com.flintclient.client.control.model.mods.ToolMods.CustomF3.HELP.enabled)) {
			module.set(com.flintclient.client.control.model.mods.ToolMods.CustomF3.HELP.enabled, help);
		}
	}

	/**
	 * Toast Control's older switches said which toasts show ({@code enabled}, {@code advancement}, {@code recipe}, {@code system},
	 * {@code tutorial}); ToastControl's say which are blocked. A file that set the older ones keeps what it showed.
	 */
	private static void toasts(ModuleState module, @Nullable JsonObject settings) {
		String[] old = {"enabled", "advancement", "recipe", "system", "tutorial"};
		BoolSetting[] block = {com.flintclient.client.control.model.mods.UtilityMods.ToastControl.BLOCK_ALL,
			com.flintclient.client.control.model.mods.UtilityMods.ToastControl.BLOCK_ADVANCEMENTS,
			com.flintclient.client.control.model.mods.UtilityMods.ToastControl.BLOCK_RECIPES,
			com.flintclient.client.control.model.mods.UtilityMods.ToastControl.BLOCK_SYSTEM,
			com.flintclient.client.control.model.mods.UtilityMods.ToastControl.BLOCK_TUTORIAL};
		for (int i = 0; i < old.length; i++) {
			Boolean shown = bool(settings, old[i]);
			if (shown != null && !has(settings, block[i])) {
				module.set(block[i], !shown);
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
		if (setting instanceof OptionSetting option) {
			return value.getAsJsonPrimitive().isString() && option.keys().contains(value.getAsString().toLowerCase(java.util.Locale.ROOT));
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
