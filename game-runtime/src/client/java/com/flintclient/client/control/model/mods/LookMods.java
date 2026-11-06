package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.IdListSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.TextSetting;
import java.util.List;

/**
 * How things look, added with the Right Shift menu's third batch: your shield's state, enchantment glint, mobs that stand
 * out, beams over dropped loot, armor and health bars with more to say, teammates you can find, and darker menus.
 */
public final class LookMods {
	private LookMods() {
	}

	public static final class ShieldStatus {
		public static final String ID = "shield_status";
		public static final BoolSetting READY = new BoolSetting("normalShieldColoration", "Ready color", "Tints your shield while it can block",
			false);
		public static final RgbSetting READY_COLOR = new RgbSetting("normalShieldColor", "Ready color", null, 0x55FF55);
		public static final BoolSetting DISABLED = new BoolSetting("disabledShieldColoration", "Disabled color",
			"Tints your shield while an axe has it disabled", true);
		public static final RgbSetting DISABLED_COLOR = new RgbSetting("disabledShieldColor", "Disabled color", null, 0xFF5555);
		public static final BoolSetting ANIMATE = new BoolSetting("animateColoration", "Fade back", "The tint eases back as the shield recovers",
			false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Shield Status", "Tint your shield by whether it can block")
			.keywords("shield", "block", "axe", "disabled", "cooldown").icon(IconId.SHIELD).category(Category.COMBAT)
			.section("General", READY, READY_COLOR, DISABLED, DISABLED_COLOR, ANIMATE).needs(Requirement.WORLD)
			.note("Your shield in first person.").build();
	}

	public static final class Glint {
		public static final String ID = "glint";
		public static final BoolSetting SHINY_POTS = new BoolSetting("shinyPots", "Shiny potions", "Potions glint as they used to", false);
		public static final OptionSetting ARMOR_MODE = mode("mode", "Armor glint");
		public static final RgbSetting ARMOR_COLOR = new RgbSetting("armorColor", "Armor color", null, 0x8040CC);
		public static final OptionSetting ITEM_MODE = mode("item", "Item glint");
		public static final RgbSetting ITEM_COLOR = new RgbSetting("itemColor", "Item color", null, 0x8040CC);
		// The glint mod's enchanted tooltips.
		public static final BoolSetting RARITY_STYLES = new BoolSetting("enableRarityTooltipStyles", "Style all items by rarity",
			"Items without enchantments get their rarity's tooltip colors", true);
		public static final BoolSetting BORDERS = new BoolSetting("enableCustomTooltipBorders", "Custom borders",
			"The tooltip's border in its enchantment's colors", true);
		public static final BoolSetting BACKGROUNDS = new BoolSetting("enableCustomTooltipBackgrounds", "Custom backgrounds",
			"The tooltip's background in its enchantment's color", true);
		public static final BoolSetting ICONS = new BoolSetting("enableEnchantmentIcons", "Enchantment icons",
			"A small icon before each enchantment's line", true);
		public static final BoolSetting GLOW = new BoolSetting("enableGlowEffect", "Glow effect", "A soft glow around the tooltip", true);
		public static final OptionSetting INTENSITY = new OptionSetting("tooltipStyleIntensity", "Style intensity",
			"How strong the tooltip's colors and glow are", 1, "low", "Low", "normal", "Normal", "high", "High");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Glint", "Change the enchantment glint on items and armor")
			.keywords("glint", "enchantment", "shine", "foil", "chroma", "tooltip", "rarity").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", SHINY_POTS).section("Armor", ARMOR_MODE, ARMOR_COLOR).section("Item", ITEM_MODE, ITEM_COLOR)
			.section("Tooltips", RARITY_STYLES, BORDERS, BACKGROUNDS, ICONS, GLOW, INTENSITY)
			.note("Armor covers worn armor and elytras; items cover everything held, dropped or in menus. Tooltips are the glint mod's: an "
				+ "enchanted item's tooltip takes its main enchantment's colors (mending first, then sharpness, fire aspect, protection, "
				+ "efficiency, fortune, silk touch, unbreaking), with an icon by each enchantment; other items take their rarity's. "
				+ "The icons are Flint's own drawings.").build();

		private static OptionSetting mode(String id, String label) {
			return new OptionSetting(id, label, null, 1, "off", "Off", "default", "Default", "custom", "Custom", "chroma", "Chroma");
		}
	}

	public static final class MobOverlay {
		public static final String ID = "mob_overlay";
		public static final OptionSetting MODE = new OptionSetting("mode", "Mode", null, 1, "outline", "Outline", "full", "Full");
		public static final RgbSetting FILL_COLOR = new RgbSetting("fillColor", "Fill color", null, 0xFFFFFF);
		public static final IntSetting FILL_OPACITY = new IntSetting("fillOpacity", "Fill opacity", null, 50, 0, 100, 5, "%");
		public static final IntSetting THICKNESS = new IntSetting("outlineThickness", "Outline thickness", null, 25, 0, 100, 25, "");
		public static final RgbSetting OUTLINE_COLOR = new RgbSetting("outlineColor", "Outline color", null, 0xFFFFFF);
		public static final BoolSetting ALWAYS = new BoolSetting("showAlways", "Show always", "Off: only the mob you look at", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Mob Overlay", "Outline or tint mobs so they stand out")
			.keywords("mob", "outline", "glow", "highlight", "esp", "tint").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", MODE, FILL_COLOR, FILL_OPACITY).section("Outline", THICKNESS, OUTLINE_COLOR, ALWAYS).needs(Requirement.WORLD).build();
	}

	/**
	 * Loot Beams Refork (Clefal, CC0 1.0 / MIT): every option of its five config files (light, loot information, sound,
	 * dynamic, custom), with its defaults and ranges. Item, tag and mod lists keep its format: tags as {@code #namespace:path},
	 * colors as {@code id=#RRGGBB}, entries separated by commas.
	 */
	public static final class LootBeams {
		public static final String ID = "loot_beams";
		// ---- light_config: beam
		public static final BoolSetting BEAM = new BoolSetting("enableBeam", "Beam", "A beam of light over each item the filter lets through",
			true);
		public static final IntSetting FADE_IN_TIME = new IntSetting("beamFadeInTime", "Beam fade-in time",
			"How many frames a new beam takes to rise to full height", 10, 1, 100, 1, " frames");
		public static final FloatSetting FADE_DISTANCE = new FloatSetting("fadeDistance", "Fade distance",
			"Past twice this distance a beam gets fainter the farther it is", 15, 1, 100, 0.5F, " blocks");
		public static final FloatSetting RADIUS = new FloatSetting("beamRadius", "Beam radius", null, 0.55F, 0, 5, 0.05F, "");
		public static final FloatSetting HEIGHT = new FloatSetting("beamHeight", "Beam height", null, 1.5F, 0, 10, 0.05F, "");
		public static final FloatSetting Y_OFFSET = new FloatSetting("beamYOffset", "Beam Y offset", null, 0.5F, -30, 30, 0.05F, "");
		public static final FloatSetting ALPHA = new FloatSetting("beamAlpha", "Beam opacity", null, 0.75F, 0, 1, 0.05F, "");
		public static final BoolSetting COMMON_SHORTER = new BoolSetting("commonShorterBeam", "Shorter common beams",
			"Common items get a beam a third shorter, without the Y offset", true);
		public static final BoolSetting ON_GROUND = new BoolSetting("requireOnGround", "Only once landed",
			"A beam shows once the item is on the ground or in water", true);
		public static final BoolSetting NAME_COLOR = new BoolSetting("nameColor", "Name color as beam color",
			"A common item named in a color gets a beam in that color", true);
		// ---- light_config: glow
		public static final BoolSetting GLOW = new BoolSetting("enableGlow", "Glow", "A soft glow on the ground under the item", true);
		public static final FloatSetting GLOW_RADIUS = new FloatSetting("glowRadius", "Glow radius", null, 0.5F, 0.01F, 1, 0.01F, "");
		// ---- light_config: custom colors
		public static final BoolSetting CUSTOM_COLOR = new BoolSetting("customColor", "Custom colors",
			"Your own beam colors by item, tag or mod; items with one always get a beam", false);
		public static final TextSetting COLOR_BY_NAME = new TextSetting("colorByName", "Colors by item",
			"minecraft:diamond=#55FFFF, … (item id=color, comma-separated)", "", 1024);
		public static final TextSetting COLOR_BY_TAG = new TextSetting("colorByTag", "Colors by tag",
			"#minecraft:swords=#FF5555, … (tag=color, comma-separated)", "", 1024);
		public static final TextSetting COLOR_BY_MOD = new TextSetting("colorByMod", "Colors by mod",
			"minecraft=#FFFFFF, … (mod id=color, comma-separated)", "", 1024);
		// ---- light_config: light effect filter
		public static final BoolSetting ALL_ITEMS = new BoolSetting("allItems", "All items", "Every dropped item gets a beam", false);
		public static final BoolSetting ONLY_RARE = new BoolSetting("onlyRare", "Only rare", "Only items at or above Rare from", false);
		public static final BoolSetting ONLY_EQUIPMENT = new BoolSetting("onlyEquipment", "Only equipment",
			"Weapons, tools of war and anything you can wear", true);
		public static final IdListSetting WHITELIST = new IdListSetting("whitelist", "Always beam these", "These items always get a beam",
			IdKind.ITEM, List.of("minecraft:totem_of_undying", "minecraft:end_crystal", "minecraft:nether_star", "minecraft:wither_skeleton_skull",
				"minecraft:diamond"));
		public static final TextSetting WHITELIST_TAGS = new TextSetting("whitelistTags", "Always beam tags",
			"Items with any of these tags (#namespace:path, comma-separated)", "", 1024);
		public static final TextSetting WHITELIST_MODS = new TextSetting("whitelistMods", "Always beam mods",
			"Items from these mods (mod ids, comma-separated)", "", 512);
		public static final IdListSetting BLACKLIST = new IdListSetting("blacklist", "Never beam these", "These items never get a beam",
			IdKind.ITEM, List.of());
		public static final TextSetting BLACKLIST_TAGS = new TextSetting("blacklistTags", "Never beam tags",
			"Items with any of these tags (#namespace:path, comma-separated)", "", 1024);
		public static final TextSetting BLACKLIST_MODS = new TextSetting("blacklistMods", "Never beam mods",
			"Items from these mods (mod ids, comma-separated)", "", 512);
		// ---- custom_config: what counts as equipment, and as rare
		public static final IdListSetting EQUIPMENT_ITEMS = new IdListSetting("equipmentItems", "Also equipment",
			"Items counted as equipment besides weapons and what you can wear", IdKind.ITEM, List.of());
		public static final TextSetting EQUIPMENT_TAGS = new TextSetting("equipmentTags", "Equipment tags",
			"Items with these tags count as equipment (#namespace:path, comma-separated)",
			"#minecraft:swords, #minecraft:axes, #forge:tools/tridents, #c:spears, #c:tools/daggers, #c:tools/clubs, #c:tools/hammers, "
				+ "#c:tools/quarterstaves, #c:tools/spears, #c:tools/knife, #c:tools/spear", 1024);
		public static final TextSetting EQUIPMENT_MODS = new TextSetting("equipmentMods", "Equipment mods",
			"Every item of these mods counts as equipment (mod ids, comma-separated)", "weaponmod", 512);
		public static final IdListSetting NOT_EQUIPMENT = new IdListSetting("notEquipment", "Never equipment",
			"Items never counted as equipment", IdKind.ITEM, List.of("weaponmod:bullet", "weaponmod:cannonball", "weaponmod:shot",
				"weaponmod:bolt", "weaponmod:shell", "weaponmod:musket-ironpart", "weaponmod:blunder-ironpart", "weaponmod:gun-stock",
				"weaponmod:mortar-ironpart", "weaponmod:dummy"));
		public static final OptionSetting RARE_FROM = new OptionSetting("rareFrom", "Rare from",
			"The lowest rarity that counts as rare (for Only rare, the drop sound and Show rarity for)", 3, "common", "Common", "uncommon",
			"Uncommon", "rare", "Rare", "epic", "Epic");
		// ---- loot_information_config
		public static final OptionSetting INFO = new OptionSetting("lootInformation", "Loot information",
			"The item's name (and rarity) over its beam, or in a tooltip while you look at it", 2, "none", "None", "name", "Name",
			"name_rarity", "Name + rarity", "tooltip", "Tooltip");
		public static final BoolSetting INFO_ALL = new BoolSetting("infoForAllItems", "Info for all items",
			"Names show for every dropped item, beam or not", true);
		public static final OptionSetting SHOW_RARITY = new OptionSetting("showRarity", "Show rarity for", null, 1, "none", "None", "rare",
			"Rare items", "all", "All items");
		public static final BoolSetting ON_LOOK = new BoolSetting("nametagOnLook", "Show when looked at",
			"Names show while you look at the item; crouching shows them all", true);
		public static final BoolSetting TEXT_BORDER = new BoolSetting("textBorder", "Text border", "A dark outline around the name", true);
		public static final BoolSetting STACK_COUNT = new BoolSetting("stackCount", "Stack count", "\" x3\" after the name of a stack", true);
		public static final FloatSetting LOOK_SENSITIVITY = new FloatSetting("lookSensitivity", "Look sensitivity",
			"How near the crosshair an item counts as looked at", 0.018F, 0, 0.2F, 0.001F, "");
		public static final FloatSetting TEXT_ALPHA = new FloatSetting("textAlpha", "Text opacity", null, 1, 0, 1, 0.05F, "");
		public static final FloatSetting BACKGROUND_ALPHA = new FloatSetting("backgroundAlpha", "Background opacity",
			"The border's (or, without one, the background's) opacity", 0.5F, 0, 1, 0.05F, "");
		public static final FloatSetting NAMETAG_SCALE = new FloatSetting("nametagScale", "Nametag scale", null, 1, 0.1F, 5, 0.05F, "×");
		public static final FloatSetting NAMETAG_Y_OFFSET = new FloatSetting("nametagYOffset", "Nametag Y offset", null, 0.75F, -5, 5, 0.05F, "");
		public static final BoolSetting TOOLTIP_CROUCH = new BoolSetting("tooltipOnCrouch", "Full tooltip on crouch",
			"Crouch for the item's whole tooltip; off, it always shows", true);
		public static final OptionSetting TOOLTIP_POSITION = new OptionSetting("tooltipPosition", "Tooltip position", null, 1, "center",
			"Center", "custom", "Custom");
		public static final IntSetting TOOLTIP_X = new IntSetting("tooltipOffsetX", "Offset from the right", null, 10, 0, 1000, 1, "");
		public static final IntSetting TOOLTIP_Y = new IntSetting("tooltipOffsetY", "Offset from the bottom", null, 72, 0, 1000, 1, "");
		// ---- sound_config
		public static final BoolSetting SOUND = new BoolSetting("sound", "Drop sound", "A chime when a rare item's beam first shows", true);
		public static final FloatSetting SOUND_VOLUME = new FloatSetting("soundVolume", "Sound volume", null, 1, 0, 1, 0.05F, "");
		public static final IdListSetting SOUND_BLACKLIST = new IdListSetting("soundBlacklist", "Silent items", "These items never chime",
			IdKind.ITEM, List.of());
		public static final TextSetting SOUND_BLACKLIST_TAGS = new TextSetting("soundBlacklistTags", "Silent tags",
			"Items with any of these tags never chime (#namespace:path, comma-separated)", "", 1024);
		public static final TextSetting SOUND_BLACKLIST_MODS = new TextSetting("soundBlacklistMods", "Silent mods",
			"Items from these mods never chime (mod ids, comma-separated)", "", 512);
		// ---- dynamic_config
		public static final BoolSetting DYNAMIC = new BoolSetting("dynamic", "Breathing beams", "Beams slowly brighten and grow, then ease back",
			true);
		public static final IntSetting HALF_ROUND = new IntSetting("halfRoundTicks", "Breath half-cycle", "Ticks to brighten (and as many to ease back)",
			30, 1, 200, 1, " ticks");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Loot Beams", "Light beams above dropped items")
			.keywords("loot", "beam", "drop", "item", "rarity", "glow", "nametag", "lootbeams").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("Beam Settings", BEAM, FADE_IN_TIME, FADE_DISTANCE, RADIUS, HEIGHT, Y_OFFSET, ALPHA, COMMON_SHORTER, ON_GROUND)
			.section("Color", NAME_COLOR, CUSTOM_COLOR, COLOR_BY_NAME, COLOR_BY_TAG, COLOR_BY_MOD)
			.section("Glow", GLOW, GLOW_RADIUS).section("Breathing", DYNAMIC, HALF_ROUND)
			.section("Which Items", ALL_ITEMS, ONLY_RARE, ONLY_EQUIPMENT, RARE_FROM, WHITELIST, WHITELIST_TAGS, WHITELIST_MODS, BLACKLIST,
				BLACKLIST_TAGS, BLACKLIST_MODS)
			.section("Equipment", EQUIPMENT_ITEMS, EQUIPMENT_TAGS, EQUIPMENT_MODS, NOT_EQUIPMENT)
			.section("Loot Information", INFO, INFO_ALL, SHOW_RARITY)
			.section("Nametag", ON_LOOK, TEXT_BORDER, STACK_COUNT, LOOK_SENSITIVITY, TEXT_ALPHA, BACKGROUND_ALPHA, NAMETAG_SCALE, NAMETAG_Y_OFFSET)
			.section("Tooltip", TOOLTIP_CROUCH, TOOLTIP_POSITION, TOOLTIP_X, TOOLTIP_Y)
			.section("Sound", SOUND, SOUND_VOLUME, SOUND_BLACKLIST, SOUND_BLACKLIST_TAGS, SOUND_BLACKLIST_MODS).needs(Requirement.WORLD)
			.note("Loot Beams Refork's beams, glow, names, tooltip and drop sound (its textures and sounds; CC0). Equipment is anything you "
				+ "can wear, shields, bows, crossbows, tridents and maces, plus the lists below. Rarity is the item's own: common white, "
				+ "uncommon yellow, rare aqua, epic pink.").build();
	}

	public static final class ArmorBar {
		public static final String ID = "armor_bar";
		public static final BoolSetting MATERIAL = new BoolSetting("materialColor", "Material colors", "Each icon in its armor's material", true);
		public static final RgbSetting LEATHER = new RgbSetting("leatherColor", "Leather", null, 0x683F22);
		public static final RgbSetting GOLD = new RgbSetting("goldColor", "Gold", null, 0xEAEE57);
		public static final RgbSetting DIAMOND = new RgbSetting("diamondColor", "Diamond", null, 0x4AEDD9);
		public static final RgbSetting NETHERITE = new RgbSetting("netheriteColor", "Netherite", null, 0x4C4449);
		public static final BoolSetting PROTECTION = new BoolSetting("protectionGlint", "Protection shimmer",
			"A soft shimmer on pieces with a protection enchantment", true);
		public static final RgbSetting ALL_PROTECTION = new RgbSetting("allProtectionColor", "Protection", null, 0x00FFFF);
		public static final RgbSetting FIRE_PROTECTION = new RgbSetting("fireProtectionColor", "Fire Protection", null, 0xFFA500);
		public static final RgbSetting BLAST_PROTECTION = new RgbSetting("blastProtectionColor", "Blast Protection", null, 0xFFFF00);
		public static final RgbSetting PROJECTILE_PROTECTION = new RgbSetting("projectileProtectionColor", "Projectile Protection", null, 0xFF00FF);
		public static final BoolSetting LOW = new BoolSetting("showArmorLow", "Low durability", "A piece close to breaking turns its own color",
			false);
		public static final IntSetting LOW_AT = new IntSetting("armorLowPercent", "Low below", null, 20, 1, 100, 1, "%");
		public static final RgbSetting LOW_COLOR = new RgbSetting("armorLowColor", "Low color", null, 0xFF0000);
		public static final BoolSetting MENDING = new BoolSetting("showMending", "Mending mark", "A dot on pieces with Mending", true);
		public static final RgbSetting MENDING_COLOR = new RgbSetting("mendingColor", "Mending color", null, 0xFFFFFF);
		public static final BoolSetting THORNS = new BoolSetting("showThorns", "Thorns mark", "A dot on pieces with Thorns", true);
		public static final RgbSetting THORNS_COLOR = new RgbSetting("thornsColor", "Thorns color", null, 0x323232);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Armor Bar", "Tint armor bar icons by material and enchantment")
			.keywords("armor", "bar", "protection", "durability", "mending", "thorns").icon(IconId.SHIELD).category(Category.COMBAT)
			.section("Material Colors", MATERIAL, LEATHER, GOLD, DIAMOND, NETHERITE)
			.section("Protection Shimmer", PROTECTION, ALL_PROTECTION, FIRE_PROTECTION, BLAST_PROTECTION, PROJECTILE_PROTECTION)
			.section("Durability", LOW, LOW_AT, LOW_COLOR).section("Marks", MENDING, MENDING_COLOR, THORNS, THORNS_COLOR)
			.needs(Requirement.WORLD).note("Iron, chainmail and turtle armor keep Minecraft's own icon.").build();
	}

	public static final class Hearts {
		public static final String ID = "hearts";
		/** Off by default: golden hearts in a row of their own above yours, as Minecraft draws them (was {@code sameBarAbsorption}, on). */
		public static final BoolSetting SAME_ROW_ABSORPTION = new BoolSetting("sameRowAbsorption", "Absorption in the same row",
			"Golden hearts over your hearts instead of a row of their own", false);
		public static final BoolSetting OVERRIDE = new BoolSetting("overrideDefaultColor", "Own base color", "Your first ten hearts in a color of your own",
			false);
		public static final RgbSetting OVERRIDE_COLOR = new RgbSetting("overrideColor", "Base color", null, 0xFF1313);
		public static final BoolSetting SHINE = new BoolSetting("shinePixel", "Shine", "The little white highlight on each heart", true);
		public static final BoolSetting EFFECT_COLORS = new BoolSetting("exposeStatusEffectColor", "Effect colors",
			"Poisoned, withered and frozen hearts look as Minecraft draws them", true);
		public static final RgbSetting[] LAYERS = new RgbSetting[12];
		private static final int[] LAYER_DEFAULTS = {0xFF8811, 0xFFFF11, 0x88FF11, 0x11FF88, 0x11FFFF, 0x2588FF, 0x1111FF, 0x8811FF, 0xFF11FF, 0xFF2A88,
			0xD32603, 0xFF1313};
		public static final ModuleDescriptor DESCRIPTOR;

		static {
			for (int i = 0; i < LAYERS.length; i++) {
				LAYERS[i] = new RgbSetting("layer" + (i + 1), "Layer " + (i + 1), i == 0 ? "Hearts past your first 20 health" : null, LAYER_DEFAULTS[i]);
			}
			DESCRIPTOR = ModuleDescriptor.builder(ID, "Hearts", "Fit all your hearts into one recolorable row")
				.keywords("hearts", "health", "absorption", "row", "color").icon(IconId.GAUGE).category(Category.VISUAL)
				.section("General", SAME_ROW_ABSORPTION, OVERRIDE, OVERRIDE_COLOR, SHINE, EFFECT_COLORS).section("Health Layers", LAYERS)
				.needs(Requirement.WORLD).note("Each 20 health past the first 20 colors the row again, from Layer 1 on.").build();
		}
	}

	public static final class TeamTracker {
		public static final String ID = "team_tracker";
		public static final BoolSetting MARKERS = new BoolSetting("nametagIndicator", "Markers", "A marker over each teammate, seen through walls",
			true);
		public static final IntSetting MARKER_SIZE = new IntSetting("indicatorSize", "Marker size", null, 7, 3, 15, 1, "");
		public static final BoolSetting DISTANCE_TEXT = new BoolSetting("indicatorDistanceText", "Distance", "How far away each teammate is", true);
		public static final BoolSetting COMPASS = new BoolSetting("compass", "Compass", "A strip on the HUD showing which way your teammates are",
			true);
		public static final BoolSetting OVERRIDE = new BoolSetting("teamColorOverride", "One color", "Every teammate in your color instead of the team's",
			false);
		public static final RgbSetting COLOR = new RgbSetting("teamColor", "Color", null, 0x55FF55);
		public static final BoolSetting LIMIT = new BoolSetting("limitVisibleDistance", "Limit distance", "Teammates further away aren't marked",
			false);
		public static final IntSetting DISTANCE = new IntSetting("distanceLimit", "Distance limit", null, 65, 8, 512, 1, " blocks");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Team Tracker", "Markers showing where your teammates are")
			.keywords("team", "teammates", "tracker", "compass", "bedwars").icon(IconId.COMPASS).category(Category.COMBAT)
			.section("Highlighting", MARKERS, MARKER_SIZE, DISTANCE_TEXT).section("Compass", COMPASS)
			.section("General", OVERRIDE, COLOR, LIMIT, DISTANCE).hud(HudPlacement.of(Anchor.TOP, 0, 64)).needs(Requirement.WORLD)
			.note("Teammates are the players on your scoreboard team (as servers set them for Bedwars and the like).").build();
	}

	public static final class DarkMode {
		public static final String ID = "dark_mode";
		public static final BoolSetting ENABLED = new BoolSetting("darkModeEnabled", "Dark menus", "Its key turns this on and off", true);
		public static final KeySetting KEY = new KeySetting("darkModeKeybind", "Dark menus key", "Turns the dark menus on and off", KeySetting.NONE);
		public static final RgbSetting SHADE = new RgbSetting("darkModeShade", "Shade", "What Minecraft's light grey becomes", 0x3C3C3C);
		public static final RgbSetting ACCENT = new RgbSetting("darkModeAccent", "Accent", "Buttons under the pointer", 0x7D7D7D);
		public static final BoolSetting HOTBAR = new BoolSetting("darkModeHotbar", "Dark hotbar", "The hotbar in the same shade", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Dark Mode", "A darker look for Minecraft's menus")
			.keywords("dark", "theme", "menus", "night", "gui").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", ENABLED, KEY, SHADE, ACCENT, HOTBAR).note("Minecraft's own menus and inventories; Flint's screens keep their theme.")
			.build();
	}

	/** The batch above, for the registry. */
	public static List<ModuleDescriptor> all() {
		return List.of(ShieldStatus.DESCRIPTOR, Glint.DESCRIPTOR, MobOverlay.DESCRIPTOR, LootBeams.DESCRIPTOR, ArmorBar.DESCRIPTOR, Hearts.DESCRIPTOR,
			TeamTracker.DESCRIPTOR, DarkMode.DESCRIPTOR);
	}
}
