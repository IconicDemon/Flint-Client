package com.flintclient.legacy.shared;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.CoordinatesLayout;
import com.flintclient.client.control.model.CpsButtons;
import com.flintclient.client.control.model.CpsFormat;
import com.flintclient.client.control.model.Decimals;
import com.flintclient.client.control.model.DirectionFormat;
import com.flintclient.client.control.model.FpsDisplayMode;
import com.flintclient.client.control.model.GameOptions;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeystrokesLayout;
import com.flintclient.client.control.model.LabelPosition;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Orientation;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.ToolMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.shaded.gson.JsonElement;
import com.flintclient.shaded.gson.JsonObject;
import com.flintclient.shaded.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Carries Flint's 1.8.9 settings from before the shared settings ({@code config/flintclient/legacy189.json}, schema 1: each
 * mod's switch, settings, HUD placement and toggle key, the menu's favourites and view, the menu's size) over to the shared files,
 * once. Each 1.8.9 mod becomes its shared module ({@link #SHARED_IDS}); each of its settings goes to the shared setting that does
 * the same, converted (a colour with its alpha becomes a colour and an opacity, a label choice the shared label switches, a key
 * code its key name, a placement the shared anchor and inward offsets). The legacy file stored every value, so a player's 1.8.9
 * HUD looks as it did. Settings the shared modules don't have are left behind (listed in docs/LEGACY189_SHARED_MENU_PLAN.md).
 *
 * <p>The caller saves the shared files and then moves the old file aside as {@code legacy189.json.bak} ({@link Result#backUp}).
 * Applying the same file again sets the same values (idempotent). No Minecraft classes: unit-tested with real files.
 */
public final class Migration189 {
	private static final Logger LOG = LogManager.getLogger("Flint");
	public static final String FILE = "legacy189.json";
	public static final String BACKUP = FILE + ".bak";

	/** Each 1.8.9 mod's id in the old file, and the shared module it is the backend of. */
	public static final Map<String, String> SHARED_IDS;

	static {
		Map<String, String> ids = new LinkedHashMap<String, String>();
		ids.put("fps", "fps");
		ids.put("ping", "ping");
		ids.put("cps", "cps");
		ids.put("keystrokes", "keystrokes");
		ids.put("coordinates", "coordinates");
		ids.put("direction", "direction");
		ids.put("armorstatus", "armor");
		ids.put("potioneffects", "effects");
		ids.put("time", "clock");
		ids.put("combodisplay", "combo_counter");
		ids.put("reachdisplay", "reach_display");
		ids.put("itemcounter", "item_counter");
		ids.put("day_counter", "day_counter");
		ids.put("saturation", "saturation");
		ids.put("scoreboard", "scoreboard");
		ids.put("nametags", "nametags");
		ids.put("waypoints", "waypoints");
		ids.put("zoom", "zoom");
		ids.put("crosshair", "crosshair");
		ids.put("motionblur", "motion_blur");
		ids.put("togglesprint", "toggle_sneak_sprint");
		ids.put("blockoverlay", "block_outline");
		ids.put("brightness", "lighting");
		ids.put("hitcolor", "animations");
		SHARED_IDS = Collections.unmodifiableMap(ids);
	}

	private Migration189() {
	}

	/** What a migration did: the old file, where it goes, and how many mods it carried. */
	public record Result(Path file, Path backup, int modules) {
		/** Moves the old file aside (replacing an older backup). False when it couldn't. */
		public boolean backUp() {
			try {
				Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
				return true;
			} catch (IOException e) {
				LOG.warn("Flint couldn't move {} aside", file, e);
				return false;
			}
		}
	}

	/**
	 * Carries {@code configDir/flintclient/legacy189.json} into {@code layout} and {@code settings} when it exists; null when there
	 * is none, or it can't be read (it is then left alone, and logged).
	 */
	public static Result migrateFile(Path configDir, Layout layout, ControlSettings settings) {
		Path file = configDir.resolve(ControlSettings.DIR).resolve(FILE);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		JsonObject root;
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonElement parsed = JsonParser.parseReader(in);
			if (!parsed.isJsonObject()) {
				throw new IOException("not a JSON object");
			}
			root = parsed.getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			LOG.warn("Flint couldn't read its old 1.8.9 settings ({}); they are left as they are", file, e);
			return null;
		}
		int modules = migrate(root, layout, settings);
		return new Result(file, file.resolveSibling(BACKUP), modules);
	}

	/** Applies an old settings file to {@code layout} and {@code settings}; returns how many mods it carried. */
	public static int migrate(JsonObject root, Layout layout, ControlSettings settings) {
		JsonObject menu = object(root, "menu");
		if (menu.has("favorites") && menu.get("favorites").isJsonArray()) {
			for (JsonElement e : menu.getAsJsonArray("favorites")) {
				String id = e.isJsonPrimitive() ? SHARED_IDS.get(e.getAsString()) : null;
				if (id != null && !settings.isFavorite(id)) {
					settings.setFavorite(id, true);
				}
			}
		}
		if (menu.has("compact") && menu.get("compact").isJsonPrimitive()) {
			settings.setCompactView(menu.get("compact").getAsBoolean());
		}
		JsonObject general = object(root, "general");
		if (general.has("menuScale") && general.get("menuScale").isJsonPrimitive()) {
			settings.setGame(GameOptions.MENU_SCALE, general.get("menuScale").getAsInt() / 100F);
		}
		int carried = 0;
		JsonObject modules = object(root, "modules");
		for (Map.Entry<String, String> e : SHARED_IDS.entrySet()) {
			if (!modules.has(e.getKey()) || !modules.get(e.getKey()).isJsonObject()) {
				continue;
			}
			int index = ModuleRegistry.indexOf(e.getValue());
			if (index < 0) {
				continue;
			}
			ModuleState state = layout.modules().get(index);
			try {
				module(e.getKey(), modules.getAsJsonObject(e.getKey()), state);
				carried++;
			} catch (RuntimeException ex) {
				LOG.warn("Flint couldn't carry over the 1.8.9 settings of {}; the rest are kept", e.getKey(), ex);
			}
		}
		return carried;
	}

	private static void module(String legacyId, JsonObject o, ModuleState state) {
		JsonObject s = object(o, "settings");
		if (o.has("enabled") && o.get("enabled").isJsonPrimitive()) {
			state.setEnabled(o.get("enabled").getAsBoolean());
		}
		if (s.has("toggleKey") && state.descriptor().modKey() != null) {
			state.set(state.descriptor().modKey(), KeyNames189.name(integer(s, "toggleKey", 0)));
		}
		if (o.has("hud") && o.get("hud").isJsonObject()) {
			state.setPlacement(placement(o.getAsJsonObject("hud"), state.placement()));
		}
		switch (legacyId) {
			case "fps" -> {
				String label = string(s, "labelMode", "suffix");
				set(state, ModuleSettings.Fps.SHOW_LABEL, !label.equals("none"));
				set(state, ModuleSettings.Fps.REVERSED, label.equals("prefix"));
				boxStyle(s, state, ModuleSettings.Fps.STYLE);
			}
			case "ping" -> {
				bool(s, "colored", state, ModuleSettings.Ping.COLOR_BY_LATENCY);
				boxStyle(s, state, ModuleSettings.Ping.STYLE);
			}
			case "cps" -> {
				set(state, ModuleSettings.Cps.BUTTONS, string(s, "buttons", "both").equals("left") ? CpsButtons.LEFT : CpsButtons.BOTH);
				// 1.8.9 showed "12 | 4".
				set(state, ModuleSettings.Cps.FORMAT, CpsFormat.BAR);
				set(state, ModuleSettings.Cps.LABEL_POSITION, label(string(s, "labelMode", "suffix")));
				boxStyle(s, state, ModuleSettings.Cps.STYLE);
			}
			case "time" -> {
				boolean h24 = bool(s, "24h", false);
				boolean seconds = bool(s, "seconds", false);
				set(state, HudMods.Clock.TIME_FORMAT, (h24 ? 0 : 2) + (seconds ? 1 : 0));
				set(state, HudMods.Clock.DATE_FORMAT, bool(s, "date", false) ? 4 : 0);
				// 1.8.9 showed the date and the time on one line.
				set(state, HudMods.Clock.ORIENTATION, 1);
				boxStyle(s, state, HudMods.Clock.STYLE);
			}
			case "combodisplay" -> {
				bool(s, "showZero", state, HudMods.ComboCounter.NO_COMBO);
				boxStyle(s, state, HudMods.ComboCounter.STYLE);
			}
			case "reachdisplay" -> {
				int hideAfter = integer(s, "hideAfter", 3);
				set(state, HudMods.ReachDisplay.RESET_AFTER, (float) hideAfter);
				set(state, HudMods.ReachDisplay.KEEP_LAST, false);
				bool(s, "textShadow", state, HudMods.ReachDisplay.SHADOW);
				bool(s, "background", state, HudMods.ReachDisplay.BACKGROUND);
				rgb(s, "textColor", state, HudMods.ReachDisplay.TEXT_COLOR);
				rgbAndOpacity(s, "backgroundColor", state, HudMods.ReachDisplay.BACKGROUND_COLOR, HudMods.ReachDisplay.BACKGROUND_OPACITY);
			}
			case "direction" -> {
				set(state, ModuleSettings.Direction.FORMAT, string(s, "format", "short").equals("long") ? DirectionFormat.NAME : DirectionFormat.COMPACT);
				bool(s, "degrees", state, ModuleSettings.Direction.SHOW_DEGREES);
				bool(s, "axis", state, ModuleSettings.Direction.SHOW_AXIS);
				look(s, state);
			}
			case "day_counter" -> {
				// Drawn as Minecraft's own line on every line now: no look of its own.
			}
			case "coordinates" -> {
				set(state, ModuleSettings.Coordinates.LAYOUT, string(s, "layout", "vertical").equals("horizontal") ? CoordinatesLayout.HORIZONTAL
					: CoordinatesLayout.VERTICAL);
				set(state, ModuleSettings.Coordinates.DECIMALS, string(s, "decimals", "0").equals("1") ? Decimals.ONE : Decimals.NONE);
				bool(s, "facing", state, ModuleSettings.Coordinates.SHOW_DIRECTION);
				if (s.has("axisColor")) {
					state.setAppearance(state.appearance().withAccentColor(palette(argb(s, "axisColor", 0xFFFF8139), true)));
				}
				look(s, state);
			}
			case "keystrokes" -> {
				boolean mouse = bool(s, "mouse", true);
				boolean space = bool(s, "space", true);
				set(state, ModuleSettings.Keystrokes.LAYOUT, !mouse ? KeystrokesLayout.KEYS : space ? KeystrokesLayout.KEYS_MOUSE_JUMP
					: KeystrokesLayout.KEYS_MOUSE);
				bool(s, "cps", state, ModuleSettings.Keystrokes.SHOW_CPS);
				// 0, 60 or 120 ms.
				set(state, ModuleSettings.Keystrokes.FADE_MS, Math.round(integer(s, "fade", 100) / 60F) * 60);
				set(state, ModuleSettings.Keystrokes.PRESSED_COLOR, palette(argb(s, "pressedColor", 0xCCFFFFFF), false));
				int pressedText = argb(s, "pressedTextColor", 0xFF000000) & 0xFFFFFF;
				set(state, ModuleSettings.Keystrokes.TEXT_ACTIVE, pressedText < 0x404040 ? HudColor.DEFAULT : palette(pressedText, false));
				if (s.has("keyColor")) {
					int key = argb(s, "keyColor", 0x66000000);
					state.setAppearance(state.appearance().withBackground(true).with(Appearance.BACKGROUND_COLOR, key & 0xFFFFFF)
						.withBackgroundOpacity(percent(key)));
				}
				if (s.has("textColor")) {
					state.setAppearance(state.appearance().withTextColor(palette(argb(s, "textColor", -1), true)));
				}
				if (s.has("textShadow")) {
					state.setAppearance(state.appearance().with(Appearance.TEXT_SHADOW, bool(s, "textShadow", true)));
				}
			}
			case "armorstatus" -> {
				String durability = string(s, "durability", "percent");
				set(state, ModuleSettings.Armor.DURABILITY, durability.equals("percent") ? 2 : durability.equals("none") ? 0 : 1);
				set(state, ModuleSettings.Armor.LAYOUT, string(s, "layout", "vertical").equals("horizontal") ? Orientation.HORIZONTAL
					: Orientation.VERTICAL);
				bool(s, "held", state, ModuleSettings.Armor.MAINHAND_DURABILITY);
				if (o.has("hud") && o.get("hud").isJsonObject()) {
					armorPlace(state, state.placement());
				}
			}
			case "potioneffects" -> {
				set(state, ModuleSettings.Effects.VIEW, string(s, "layout", "tiles").equals("list") ? ModuleSettings.Effects.LIST
					: ModuleSettings.Effects.TILES);
				bool(s, "splitHarmful", state, ModuleSettings.Effects.TILE_SPLIT);
				bool(s, "showTime", state, ModuleSettings.Effects.SHOW_DURATION);
				bool(s, "icons", state, ModuleSettings.Effects.SHOW_ICONS);
				bool(s, "names", state, ModuleSettings.Effects.SHOW_NAMES);
				bool(s, "blink", state, ModuleSettings.Effects.BLINK_ENDING);
				rgb(s, "timeColor", state, ModuleSettings.Effects.TIME_COLOR);
				look(s, state);
				if (s.has("nameColor")) {
					state.setAppearance(state.appearance().withTextColor(palette(argb(s, "nameColor", -1), true)));
				}
			}
			case "itemcounter" -> {
				bool(s, "goldenApples", state, HudMods.ItemCounter.GOLDEN_APPLES);
				bool(s, "enderPearls", state, HudMods.ItemCounter.PEARLS);
				bool(s, "arrows", state, HudMods.ItemCounter.ARROWS);
				bool(s, "hideZero", state, HudMods.ItemCounter.SKIP_ZERO);
				set(state, HudMods.ItemCounter.LAYOUT, string(s, "layout", "vertical").equals("horizontal") ? 0 : 1);
				List<String> extra = new ArrayList<String>();
				if (bool(s, "splashHealing", true)) {
					extra.add("minecraft:splash_potion");
				}
				if (bool(s, "soup", false)) {
					extra.add("minecraft:mushroom_stew");
				}
				set(state, HudMods.ItemCounter.EXTRA, String.join(", ", extra));
				look(s, state);
			}
			case "saturation" -> {
				bool(s, "hud", state, HudMods.Saturation.HUD);
				bool(s, "showFood", state, HudMods.Saturation.SHOW_FOOD);
				bool(s, "overlay", state, HudMods.Saturation.OVERLAY);
				if (s.has("color")) {
					set(state, HudMods.Saturation.COLOR, argb(s, "color", 0xFFFFC93C) & 0xFFFFFF);
					set(state, HudMods.Saturation.COLOR_OVERLAY, true);
				}
				boxStyle(s, state, HudMods.Saturation.STYLE);
			}
			case "scoreboard" -> {
				bool(s, "showNumbers", state, UtilityMods.Scoreboard.NUMBERS);
				bool(s, "textShadow", state, UtilityMods.Scoreboard.SHADOW);
				rgb(s, "textColor", state, UtilityMods.Scoreboard.TEXT_COLOR);
				rgbAndOpacity(s, "backgroundColor", state, UtilityMods.Scoreboard.BACKGROUND_COLOR, UtilityMods.Scoreboard.OPACITY);
				if (!bool(s, "background", true)) {
					set(state, UtilityMods.Scoreboard.OPACITY, 0);
				}
				rgb(s, "titleBackground", state, UtilityMods.Scoreboard.TITLE_BACKGROUND);
				bool(s, "border", state, UtilityMods.Scoreboard.BORDER);
				rgb(s, "borderColor", state, UtilityMods.Scoreboard.BORDER_COLOR);
			}
			case "nametags" -> {
				bool(s, "showOwn", state, VisualMods.Nametags.OWN);
				bool(s, "textShadow", state, VisualMods.Nametags.SHADOW);
				bool(s, "background", state, VisualMods.Nametags.BACKGROUND);
				integer(s, "backgroundOpacity", state, VisualMods.Nametags.BACKGROUND_OPACITY);
			}
			case "waypoints" -> {
				bool(s, "deathWaypoints", state, UtilityMods.Waypoints.ON_DEATH);
				integer(s, "deathLimit", state, UtilityMods.Waypoints.DEATH_LIMIT);
				bool(s, "randomColors", state, UtilityMods.Waypoints.RANDOM_COLORS);
				option(s, "sort", state, UtilityMods.Waypoints.SORT);
				integer(s, "hideBeyond", state, UtilityMods.Waypoints.MAX_DISTANCE);
				option(s, "display", state, UtilityMods.Waypoints.DISPLAY);
				option(s, "labelStyle", state, UtilityMods.Waypoints.LABEL_STYLE);
				if (s.has("labelScale")) {
					set(state, UtilityMods.Waypoints.LABEL_SCALE, integer(s, "labelScale", 100) / 100F);
				}
				if (s.has("labelHeight")) {
					set(state, UtilityMods.Waypoints.LABEL_HEIGHT, integer(s, "labelHeight", 25) / 10F);
				}
				integer(s, "labelOpacity", state, UtilityMods.Waypoints.LABEL_OPACITY);
				bool(s, "showDistance", state, UtilityMods.Waypoints.SHOW_DISTANCE);
				bool(s, "distanceFacing", state, UtilityMods.Waypoints.DISTANCE_FACING);
				bool(s, "textShadow", state, UtilityMods.Waypoints.TEXT_SHADOW);
				integer(s, "shadowOpacity", state, UtilityMods.Waypoints.SHADOW_OPACITY);
				bool(s, "throughWalls", state, UtilityMods.Waypoints.THROUGH_WALLS);
				integer(s, "outlineOpacity", state, UtilityMods.Waypoints.OUTLINE_OPACITY);
				integer(s, "fillOpacity", state, UtilityMods.Waypoints.FILL_OPACITY);
				if (s.has("outlineThickness")) {
					set(state, UtilityMods.Waypoints.OUTLINE_THICKNESS, integer(s, "outlineThickness", 20) / 10F);
				}
				key(s, "createKey", state, UtilityMods.Waypoints.CREATE_KEY);
				key(s, "hideKey", state, UtilityMods.Waypoints.TOGGLE_KEY);
				key(s, "listKey", state, UtilityMods.Waypoints.MENU_KEY);
			}
			case "zoom" -> {
				set(state, PlayerMods.Zoom.MODE, string(s, "mode", "hold").equals("toggle") ? 1 : 0);
				integer(s, "level", state, PlayerMods.Zoom.LEVEL);
				bool(s, "scroll", state, PlayerMods.Zoom.SCROLL);
				if (s.has("smooth") && !bool(s, "smooth", true)) {
					set(state, PlayerMods.Zoom.IN_TRANSITION, 0);
					set(state, PlayerMods.Zoom.OUT_TRANSITION, 0);
				}
				if (s.has("slowerMouse")) {
					set(state, PlayerMods.Zoom.RELATIVE_SENSITIVITY, bool(s, "slowerMouse", true) ? 100 : 0);
				}
				if (s.has("cinematic")) {
					set(state, PlayerMods.Zoom.CINEMATIC, bool(s, "cinematic", false) ? 100 : 0);
				}
			}
			case "crosshair" -> {
				String style = string(s, "style", "cross");
				set(state, PlayerMods.Crosshair.TYPE, style.equals("circle") ? 2 : 1);
				boolean dotOnly = style.equals("dot");
				set(state, PlayerMods.Crosshair.DOT, dotOnly || style.equals("crossdot"));
				if (s.has("color")) {
					int c = argb(s, "color", -1);
					set(state, PlayerMods.Crosshair.COLOR, c & 0xFFFFFF);
					set(state, PlayerMods.Crosshair.DOT_COLOR, c & 0xFFFFFF);
					set(state, PlayerMods.Crosshair.ALPHA, c >>> 24);
				}
				// 1.8.9 measured in window pixels; the shared crosshair in GUI units (two pixels each at the usual GUI scale).
				int arm = dotOnly ? 0 : Math.max(1, Math.round(integer(s, "size", 5) / 2F));
				set(state, PlayerMods.Crosshair.WIDTH, arm);
				set(state, PlayerMods.Crosshair.HEIGHT, arm);
				set(state, PlayerMods.Crosshair.GAP, Math.round(integer(s, "gap", 2) / 2F));
				set(state, PlayerMods.Crosshair.THICKNESS, Math.max(1, Math.round(integer(s, "thickness", 2) / 2F)));
				bool(s, "outline", state, PlayerMods.Crosshair.OUTLINE);
				if (s.has("outlineColor")) {
					int c = argb(s, "outlineColor", 0xC0000000);
					set(state, PlayerMods.Crosshair.OUTLINE_COLOR, c & 0xFFFFFF);
					set(state, PlayerMods.Crosshair.OUTLINE_ALPHA, c >>> 24);
				}
				bool(s, "invert", state, PlayerMods.Crosshair.INVERT);
			}
			case "motionblur" -> {
				if (s.has("amount")) {
					set(state, VisualMods.MotionBlur.STRENGTH, integer(s, "amount", 5) / 5F);
				}
			}
			case "togglesprint" -> {
				bool(s, "toggleSprint", state, PlayerMods.ToggleSprint.SPRINT);
				bool(s, "toggleSneak", state, PlayerMods.ToggleSprint.SNEAK);
				if (s.has("hud")) {
					set(state, PlayerMods.ToggleSprint.HIDE_TEXT, !bool(s, "hud", true));
				}
				boxStyle(s, state, PlayerMods.ToggleSprint.STYLE);
			}
			case "blockoverlay" -> {
				if (s.has("outlineColor")) {
					int c = argb(s, "outlineColor", 0x66000000);
					set(state, VisualMods.BlockOutline.COLOR, c & 0xFFFFFF);
					// The shared outline is always drawn: "off" becomes its faintest.
					set(state, VisualMods.BlockOutline.OPACITY, bool(s, "outline", true) ? percent(c) : 10);
				}
				if (s.has("outlineWidth")) {
					// Minecraft's own line is 2 pixels: 100%.
					set(state, VisualMods.BlockOutline.THICKNESS, integer(s, "outlineWidth", 2) * 50);
				}
				bool(s, "fill", state, VisualMods.BlockOutline.FILL);
				rgbAndOpacity(s, "fillColor", state, VisualMods.BlockOutline.FILL_COLOR, VisualMods.BlockOutline.FILL_OPACITY);
			}
			case "brightness" -> {
				int level = integer(s, "level", 1000);
				set(state, VisualMods.Lighting.MODE, level >= 1000 ? 0 : 1);
				set(state, VisualMods.Lighting.BRIGHTNESS, level);
			}
			case "hitcolor" -> {
				// The mod was the damage colour: on is the Animations module with its custom damage colour.
				set(state, ToolMods.Animations.CUSTOM_DAMAGE, true);
				rgb(s, "color", state, ToolMods.Animations.DAMAGE_COLOR);
				integer(s, "strength", state, ToolMods.Animations.DAMAGE_STRENGTH);
			}
			default -> {
			}
		}
	}

	// ---------- the shared looks ----------

	/** A counter's style (HudModule's text, shadow, panel, border and padding, TextHud's brackets) into its box style. */
	private static void boxStyle(JsonObject s, ModuleState state, BoxStyle style) {
		if (bool(s, "brackets", false)) {
			set(state, style.displayMode, FpsDisplayMode.BRACKETS);
		}
		bool(s, "background", state, style.background);
		rgb(s, "textColor", state, style.textColor);
		bool(s, "textShadow", state, style.textShadow);
		rgbAndOpacity(s, "backgroundColor", state, style.backgroundColor, style.backgroundOpacity);
		bool(s, "border", state, style.border);
		rgb(s, "borderColor", state, style.borderColor);
		if (s.has("borderThickness")) {
			// Pixels then, half units now.
			set(state, style.borderThickness, 2 * integer(s, "borderThickness", 1));
		}
		integer(s, "horizontalPadding", state, style.horizontalPadding);
		integer(s, "verticalPadding", state, style.verticalPadding);
	}

	/** The same style into the module's own HUD look (a module the shared HUD look draws). */
	private static void look(JsonObject s, ModuleState state) {
		Appearance a = state.appearance();
		if (s.has("background")) {
			a = a.withBackground(bool(s, "background", true));
		}
		if (s.has("backgroundColor")) {
			int c = argb(s, "backgroundColor", 0x6F000000);
			a = a.with(Appearance.BACKGROUND_COLOR, c & 0xFFFFFF).withBackgroundOpacity(percent(c));
		}
		if (s.has("textColor")) {
			a = a.withTextColor(palette(argb(s, "textColor", -1), true));
		}
		if (s.has("textShadow")) {
			a = a.with(Appearance.TEXT_SHADOW, bool(s, "textShadow", true));
		}
		if (s.has("border")) {
			a = a.with(Appearance.BORDER, bool(s, "border", false));
		}
		if (s.has("borderColor")) {
			a = a.with(Appearance.BORDER_COLOR, argb(s, "borderColor", 0x60FFFFFF) & 0xFFFFFF);
		}
		if (s.has("borderThickness")) {
			a = a.with(Appearance.BORDER_THICKNESS, Math.min(4, 2 * integer(s, "borderThickness", 1)));
		}
		if (s.has("horizontalPadding")) {
			a = a.withPadding(Math.max(0, Math.min(8, integer(s, "horizontalPadding", 5))));
		}
		state.setAppearance(a);
	}

	/** The shared palette colour nearest {@code argb}; with {@code whiteIsDefault}, white means the style's own (it was the default). */
	static HudColor palette(int argb, boolean whiteIsDefault) {
		int rgb = argb & 0xFFFFFF;
		if (whiteIsDefault && rgb == 0xFFFFFF) {
			return HudColor.DEFAULT;
		}
		HudColor best = HudColor.WHITE;
		long bestD = Long.MAX_VALUE;
		for (HudColor c : HudColor.values()) {
			if (c.isDefault()) {
				continue;
			}
			int o = c.argb();
			long dr = (o >> 16 & 255) - (rgb >> 16 & 255);
			long dg = (o >> 8 & 255) - (rgb >> 8 & 255);
			long db = (o & 255) - (rgb & 255);
			long d = dr * dr + dg * dg + db * db;
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		return best;
	}

	/**
	 * Armor is placed as uku's Armor HUD places it on every line (an anchor, a side and offsets), not by a HUD placement: the old
	 * 1.8.9 Armor Status' spot becomes the same corner or edge with the same inset, so it stays where it was.
	 */
	static void armorPlace(ModuleState state, HudPlacement at) {
		float fx = at.anchor().fx();
		float fy = at.anchor().fy();
		int anchor = fy == 0 ? (fx == 0.5F ? 0 : 1) : fy == 0.5F ? 2 : 3;
		set(state, ModuleSettings.Armor.ANCHOR, anchor);
		set(state, ModuleSettings.Armor.SIDE, fx == 1 ? 1 : 0);
		set(state, ModuleSettings.Armor.OFFSET_X, anchor == 0 ? 0 : Math.round(at.x()));
		set(state, ModuleSettings.Armor.OFFSET_Y, Math.round(at.y()));
	}

	/** An old placement (anchor column and row 0..2, offsets measured from the anchor, scale) as the shared one. */
	static HudPlacement placement(JsonObject hud, HudPlacement fallback) {
		if (!hud.has("anchorX") || !hud.has("anchorY")) {
			return fallback;
		}
		int ax = Math.max(0, Math.min(2, hud.get("anchorX").getAsInt()));
		int ay = Math.max(0, Math.min(2, hud.get("anchorY").getAsInt()));
		float x = hud.has("x") ? hud.get("x").getAsFloat() : 0;
		float y = hud.has("y") ? hud.get("y").getAsFloat() : 0;
		float scale = hud.has("scale") ? hud.get("scale").getAsFloat() : 1;
		// Shared offsets point inward from the anchored edge: right and bottom ones flip.
		return new HudPlacement(Anchor.of(ax, ay), ax == 2 ? -x : x, ay == 2 ? -y : y, scale);
	}

	private static LabelPosition label(String mode) {
		return mode.equals("prefix") ? LabelPosition.BEFORE : mode.equals("none") ? LabelPosition.HIDDEN : LabelPosition.AFTER;
	}

	/** An opacity in percent from {@code argb}'s alpha. */
	static int percent(int argb) {
		return Math.round((argb >>> 24) * 100 / 255F);
	}

	// ---------- reading the old values ----------

	private static JsonObject object(JsonObject parent, String key) {
		JsonElement e = parent.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	private static boolean bool(JsonObject s, String key, boolean fallback) {
		JsonElement e = s.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
	}

	private static int integer(JsonObject s, String key, int fallback) {
		JsonElement e = s.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : fallback;
	}

	private static String string(JsonObject s, String key, String fallback) {
		JsonElement e = s.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	/** A colour as the old file wrote it ("#AARRGGBB", or "#RRGGBB" for an opaque one). */
	private static int argb(JsonObject s, String key, int fallback) {
		String text = string(s, key, null);
		if (text == null) {
			return fallback;
		}
		try {
			String hex = text.replace("#", "");
			long v = Long.parseLong(hex, 16);
			return hex.length() <= 6 ? (int) v | 0xFF000000 : (int) v;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	// ---------- writing the shared values ----------

	private static <T> void set(ModuleState state, SettingDescriptor<T> setting, T value) {
		state.set(setting, value);
	}

	private static void bool(JsonObject s, String key, ModuleState state, BoolSetting setting) {
		if (s.has(key)) {
			set(state, setting, bool(s, key, setting.defaultValue()));
		}
	}

	private static void integer(JsonObject s, String key, ModuleState state, IntSetting setting) {
		if (s.has(key)) {
			set(state, setting, integer(s, key, setting.defaultValue()));
		}
	}

	private static void option(JsonObject s, String key, ModuleState state, com.flintclient.client.control.model.OptionSetting setting) {
		if (s.has(key)) {
			int i = setting.indexOf(string(s, key, ""));
			if (i >= 0) {
				set(state, setting, i);
			}
		}
	}

	private static void rgb(JsonObject s, String key, ModuleState state, RgbSetting setting) {
		if (s.has(key)) {
			set(state, setting, argb(s, key, 0xFF000000 | setting.defaultValue()) & 0xFFFFFF);
		}
	}

	private static void rgbAndOpacity(JsonObject s, String key, ModuleState state, RgbSetting color, IntSetting opacityPercent) {
		if (s.has(key)) {
			int c = argb(s, key, 0xFF000000 | color.defaultValue());
			set(state, color, c & 0xFFFFFF);
			set(state, opacityPercent, percent(c));
		}
	}

	private static void key(JsonObject s, String key, ModuleState state, com.flintclient.client.control.model.KeySetting setting) {
		if (s.has(key)) {
			set(state, setting, KeyNames189.name(integer(s, key, 0)));
		}
	}
}
