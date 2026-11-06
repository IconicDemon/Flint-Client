package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.FpsSizing;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.TextSetting;
import java.util.ArrayList;
import java.util.List;

/** Information counters on the HUD: one boxed line each (the {@link BoxStyle} look), or a short list. */
public final class HudMods {
	private HudMods() {
	}

	/** "Prefix / Suffix / None" for counters with a word next to the number. */
	static OptionSetting labelMode(String example) {
		return new OptionSetting("labelMode", "Label", example, 1, "prefix", "Prefix", "suffix", "Suffix", "none", "None");
	}

	public static final class MemoryUsage {
		public static final String ID = "memory_usage";
		public static final BoxStyle STYLE = new BoxStyle(60, 13);
		public static final OptionSetting FORMAT = new OptionSetting("format", "Format", "Percent of the most Java may use, or the amounts",
			0, "percent", "Percent", "amount", "Amount", "both", "Both");
		public static final BoolSetting SHOW_LABEL = new BoolSetting("showLabel", "Show “MEM”", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Memory Usage", "Display how much memory the game uses")
			.keywords("ram", "heap", "memory", "java").icon(IconId.GAUGE).category(Category.PERFORMANCE)
			.section(STYLE.section("Style")).section("Memory", FORMAT, SHOW_LABEL).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 19))
			.note("Java's heap in use against the most it may grow to, read four times a second").build();
	}

	/** Playtime: ptdisplay's playtime (the Play Time statistic), as plain text in its colour and shadow. */
	public static final class Playtime {
		public static final String ID = "playtime";
		public static final RgbSetting COLOR = new RgbSetting("color", "Color", "Color of the overlay text", 0xFFFFFF);
		public static final BoolSetting DROP_SHADOW = new BoolSetting("dropShadow", "Drop shadow", "Drop shadow for the overlay text", true);
		public static final BoolSetting STAT_FORMAT = new BoolSetting("useStatFormat", "Use stat format",
			"Minecraft's statistics format (like 1.25 h) instead of 1:15:00", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Playtime", "Show your total playtime in this world")
			.keywords("time", "played", "playtime", "statistic", "ptdisplay").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", COLOR, DROP_SHADOW, STAT_FORMAT).drawnByMinecraft()
			.hud(HudPlacement.of(Anchor.TOP_LEFT, 4, 4)).needs(Requirement.WORLD)
			.note("Your Play Time statistic, asked of the server once a second (as the Statistics screen asks for it). Move it in the HUD "
				+ "editor.").build();
	}

	/** Day Counter: the day counter datapack's "DAY 12", where the action bar shows. */
	public static final class DayCounter {
		public static final String ID = "day_counter";
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Day Counter", "Count the day in real time above your hotbar")
			.keywords("day", "days", "time", "world", "daycounter").icon(IconId.GAUGE).category(Category.WORLD).drawnByMinecraft()
			.hud(HudPlacement.of(Anchor.BOTTOM, 0, 63)).needs(Requirement.WORLD)
			.note("The world's day as Minecraft counts it (its time divided by 24000), where the action bar shows; a server's own "
				+ "action bar message shows instead while it's up.").build();
	}

	public static final class ViewAngles {
		public static final String ID = "view_angles";
		public static final BoxStyle STYLE = new BoxStyle(90, 13);
		public static final OptionSetting DECIMALS = new OptionSetting("decimals", "Decimals", null, 1, "0", "0", "1", "0.0", "2", "0.00");
		public static final BoolSetting LABELS = new BoolSetting("labels", "Labels", "Yaw and Pitch before the numbers", true);
		public static final BoolSetting WRAP = new BoolSetting("wrapYaw", "Wrap yaw", "Keeps yaw between -180 and 180, as F3 shows it", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "View Angles", "Display your yaw and pitch")
			.keywords("yaw", "pitch", "rotation", "angle").icon(IconId.COMPASS).category(Category.WORLD)
			.section(STYLE.section("Style")).section("Angles", DECIMALS, LABELS, WRAP).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 64)).needs(Requirement.WORLD).build();
	}

	public static final class HeightLimit {
		public static final String ID = "height_limit";
		public static final BoxStyle STYLE = new BoxStyle(90, 13);
		public static final OptionSetting SHOW = new OptionSetting("show", "Show", "Blocks left before the top, the bottom, or both", 0, "top",
			"To top", "bottom", "To bottom", "both", "Both");
		public static final BoolSetting ONLY_NEAR = new BoolSetting("onlyNear", "Only when close", "Hidden until you are near the limit", false);
		public static final IntSetting NEAR = new IntSetting("nearDistance", "Close means", "Within this many blocks", 16, 2, 64, 1, " blocks");
		public static final RgbSetting WARN_COLOR = new RgbSetting("warnColor", "Warning color", "Used within 5 blocks of a limit", 0xFF5555);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Height Limit",
				"Display how far you are from the build limits")
			.keywords("build limit", "height", "world height", "y").icon(IconId.PIN).category(Category.WORLD)
			.section(STYLE.section("Style")).section("Height", SHOW, ONLY_NEAR, NEAR, WARN_COLOR).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP, 0, 40)).needs(Requirement.WORLD)
			.note("The limits of the dimension you are in, as the world reports them").build();
	}

	public static final class Saturation {
		public static final String ID = "saturation";
		public static final BoxStyle STYLE = new BoxStyle(60, 13);
		public static final BoolSetting HUD = new BoolSetting("hud", "HUD", "The saturation counter on screen", true);
		public static final BoolSetting SHOW_FOOD = new BoolSetting("showFood", "Show hunger", "Adds your food points, like 17 · 5.0", false);
		public static final BoolSetting SHOW_LABEL = new BoolSetting("showLabel", "Show “Sat”", true);
		/** AppleSkin's "Show food values in tooltip" and "Always show food values in tooltip" (both on: Always). */
		public static final OptionSetting TOOLTIP = new OptionSetting("tooltipFood", "Tooltip food values",
			"The hunger and saturation a food restores, as icons at the bottom of its tooltip", 2, "never", "Never", "shift",
			"Shift Only", "always", "Always");
		public static final BoolSetting OVERLAY = new BoolSetting("overlay", "Saturation overlay",
			"Your current saturation level, outlined on the hunger bar", true);
		public static final RgbSetting COLOR = new RgbSetting("color", "Saturation color",
			"The colour of the outlines it is switched on for (off: AppleSkin's gold); Flint's look is red", 0xFF0000);
		public static final BoolSetting COLOR_OVERLAY = new BoolSetting("colorOverlay", "Color the overlay",
			"The saturation overlay in Saturation color", true);
		public static final BoolSetting COLOR_GAIN = new BoolSetting("colorGain", "Color the gain overlay",
			"The flashing saturation gain in Saturation color", true);
		public static final BoolSetting COLOR_TOOLTIP = new BoolSetting("colorTooltip", "Color the tooltip saturation",
			"The saturation icons in food tooltips in Saturation color", true);
		public static final BoolSetting EXHAUSTION = new BoolSetting("exhaustion", "Exhaustion underlay",
			"Your food exhaustion, as a bar behind the hunger bar that fills toward your next hunger point", true);
		public static final BoolSetting FOOD_GAIN = new BoolSetting("foodGain", "Food gain overlay",
			"The hunger (and saturation, with the overlays below on) the food you hold would restore, flashing", true);
		public static final BoolSetting SATURATION_GAIN = new BoolSetting("saturationGain", "Saturation gain overlay",
			"The saturation the food you hold would add, flashing over the saturation overlay", true);
		public static final BoolSetting OFFHAND = new BoolSetting("offhand", "Food in off-hand",
			"The gain overlays also for food in your off-hand (when your main hand holds none you could eat)", true);
		public static final BoolSetting SHAKE = new BoolSetting("vanillaAnimations", "Animate with Minecraft's icons",
			"The overlays shake with the hunger bar and hearts as Minecraft animates them", true);
		public static final FloatSetting FLASH_ALPHA = new FloatSetting("maxFlashAlpha", "Max flash alpha",
			"How opaque the flashing icons get at their most visible (1 opaque, 0 clear)", 0.65F, 0, 1, 0.05F, "");
		public static final IntSetting ANIMATION_SPEED = new IntSetting("animationSpeed", "Animation speed",
			"How fast the gain overlays flash (10: once every 1.6 s, as AppleSkin)", 10, 1, 25, 1, "");
		public static final BoolSetting HEALTH_GAIN = new BoolSetting("healthGain", "Health gain overlay",
			"The health the food you hold would restore, flashing on your hearts (below 18 hunger, while hurt)", true);
		public static final BoolSetting NATURAL_REGEN = new BoolSetting("naturalRegen", "Natural regen",
			"Count what natural regeneration heals from the food (where the natural health regeneration rule allows it)", true);
		/** AppleSkin's debug screen entry (hunger, saturation and exhaustion), kept in step with Minecraft's Debug Options. */
		public static final OptionSetting DEBUG = new OptionSetting("debugInfo", "Food values in debug screen",
			"\"hunger: 20, sat: 5, exh: 0.00/4\" on the debug screen; the same switch as food_stats in Minecraft's Debug Options", 0,
			"never", "Never", "overlay", "With F3", "always", "Always");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Saturation", "Adds saturation visualization to food")
			.keywords("food", "hunger", "saturation", "eat", "exhaustion", "appleskin").icon(IconId.FLASK).category(Category.UTILITY)
			.section("General", HUD, SHOW_FOOD, SHOW_LABEL, TOOLTIP, ANIMATION_SPEED, FLASH_ALPHA, SHAKE, OFFHAND, DEBUG)
			.section("Hunger Bar", OVERLAY, EXHAUSTION, FOOD_GAIN, SATURATION_GAIN)
			.section("Color", COLOR, COLOR_GAIN, COLOR_OVERLAY, COLOR_TOOLTIP)
			.section("Health Gain", HEALTH_GAIN, NATURAL_REGEN)
			.section(STYLE.section("Style")).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP, 0, 55)).needs(Requirement.WORLD)
			.note("AppleSkin's overlays, with its look and timing. In single player and on your own LAN world Flint reads your "
				+ "saturation and exhaustion from the game you host, as AppleSkin's server half sends them; on a server, saturation is "
				+ "what the server sends and exhaustion stays at zero (servers don't send it). A food's values are the ones the game knows "
				+ "for it; food that makes you sick shows in the hunger colours, and a golden apple's regeneration counts in its health "
				+ "gain.").build();
	}

	/** Potion Counter: uku's PotionCounter, your splash potions by effect with how many of each. */
	public static final class PotionCounter {
		public static final String ID = "potion_counter";
		public static final BoolSetting SHOW_UPGRADES = new BoolSetting("showUpgrades", "Show potion upgrades",
			"Each kind of potion on its own row, with redstone for longer ones and glowstone for stronger ones", false);
		/** Every effect a potion of Minecraft's can give, in the order its potions list them: shown or not ("Toggle individual potions"). */
		public static final String[] EFFECTS = {"night_vision", "invisibility", "jump_boost", "fire_resistance", "speed", "slowness", "resistance",
			"water_breathing", "instant_health", "instant_damage", "poison", "regeneration", "strength", "weakness", "luck", "slow_falling",
			"wind_charged", "weaving", "oozing", "infested"};
		private static final String[] EFFECT_NAMES = {"Night Vision", "Invisibility", "Jump Boost", "Fire Resistance", "Speed", "Slowness",
			"Resistance", "Water Breathing", "Instant Health", "Instant Damage", "Poison", "Regeneration", "Strength", "Weakness", "Luck",
			"Slow Falling", "Wind Charged", "Weaving", "Oozing", "Infested"};
		public static final BoolSetting[] SHOWN = shown();
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Potion Counter",
				"Shows how many potions you have left in your inventory")
			.keywords("pots", "potions", "splash", "pvp", "potioncounter").icon(IconId.FLASK).category(Category.COMBAT)
			.section("General", SHOW_UPGRADES).section("Toggle Individual Potions", SHOWN).drawnByMinecraft()
			.hud(HudPlacement.of(Anchor.TOP_LEFT, 5, 5)).needs(Requirement.WORLD)
			.note("Splash potions in your inventory (not your hotbar's offhand), grouped by their effect. On the right half of the screen "
				+ "the list lines up on the right, and on the bottom half it grows upwards, as the original does.").build();

		private static BoolSetting[] shown() {
			BoolSetting[] out = new BoolSetting[EFFECTS.length];
			for (int i = 0; i < out.length; i++) {
				String id = EFFECTS[i];
				StringBuilder key = new StringBuilder("show");
				for (String part : id.split("_")) {
					key.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
				}
				out[i] = new BoolSetting(key.toString(), EFFECT_NAMES[i], true);
			}
			return out;
		}
	}

	/** Reach Display: Player Reach Display's distance of your last hit, its colours, bands, filter and reset. */
	public static final class ReachDisplay {
		public static final String ID = "reach_display";
		public static final int MAX_BANDS = 5;
		// ---------- Appearance ----------
		public static final BoolSetting SHADOW = new BoolSetting("showShadow", "Shadow", "A shadow under the text, in the shadow color", true);
		public static final BoolSetting BACKGROUND = new BoolSetting("showBackground", "Background", "A box behind the text", true);
		public static final RgbSetting TEXT_COLOR = new RgbSetting("textColor", "Text color", null, 0xFFFFFF);
		public static final RgbSetting SHADOW_COLOR = new RgbSetting("shadowColor", "Shadow color", null, 0x000000);
		public static final RgbSetting BACKGROUND_COLOR = new RgbSetting("backgroundColor", "Background color", null, 0x000000);
		public static final IntSetting BACKGROUND_OPACITY = new IntSetting("backgroundOpacity", "Background opacity", null, 31, 0, 100, 1, "%");
		public static final IntSetting SCALE = new IntSetting("scale", "Scale", null, 100, 10, 400, 1, "%");
		public static final OptionSetting DISPLAY_MODE = new OptionSetting("displayMode", "Display mode", "2.94, 2.94 blocks or 2.94 M", 1,
			"number_only", "Number Only", "with_blocks", "With Blocks", "with_m", "With M");
		// ---------- Behaviour ----------
		public static final BoolSetting KEEP_LAST = new BoolSetting("keepLastHitDistance", "Keep last hit distance",
			"Off: back to 0.00 after Reset after", true);
		public static final FloatSetting RESET_AFTER = new FloatSetting("resetAfterSeconds", "Reset after", "Seconds after a hit", 15, 0, 60, 0.1F,
			" s");
		// ---------- Entity filter ----------
		public static final BoolSetting FILTER = new BoolSetting("enableEntityFilter", "Entity filter", "Only some kinds of entity count", false);
		public static final OptionSetting FILTER_MODE = new OptionSetting("filterMode", "Filter mode", "Whitelist: only these. Blacklist: all but these",
			0, "whitelist", "Whitelist", "blacklist", "Blacklist");
		public static final TextSetting WHITELIST = new TextSetting("whitelist", "Whitelist", "Entity ids, comma separated, like player, zombie", "",
			1000);
		public static final TextSetting BLACKLIST = new TextSetting("blacklist", "Blacklist", "Entity ids, comma separated, like armor_stand", "",
			1000);
		// ---------- Distance colors ----------
		public static final BoolSetting DISTANCE_COLOR = new BoolSetting("enableDistanceColor", "Distance color",
			"The colors of the band the distance falls in", false);
		public static final IntSetting BANDS = new IntSetting("distanceBands", "Bands", null, 4, 2, MAX_BANDS, 1, "");
		private static final float[] BAND_FROM = {0, 1, 2, 3, 4};
		private static final int[] BAND_TEXT = {0x00FF00, 0xFFFF00, 0xFFA500, 0xFF0000, 0xFF0000};
		private static final int[] BAND_SHADOW = {0x003300, 0x333300, 0x331A00, 0x330000, 0x330000};
		private static final int[] BAND_BACKGROUND = {0x003300, 0xFFFF00, 0xFFA500, 0xFF0000, 0xFF0000};
		private static final int[] BAND_OPACITY = {25, 25, 25, 25, 25};
		public static final FloatSetting[] FROM = new FloatSetting[MAX_BANDS];
		public static final RgbSetting[] BAND_TEXT_COLOR = new RgbSetting[MAX_BANDS];
		public static final RgbSetting[] BAND_SHADOW_COLOR = new RgbSetting[MAX_BANDS];
		public static final RgbSetting[] BAND_BACKGROUND_COLOR = new RgbSetting[MAX_BANDS];
		public static final IntSetting[] BAND_BACKGROUND_OPACITY = new IntSetting[MAX_BANDS];

		static {
			for (int i = 0; i < MAX_BANDS; i++) {
				int n = i + 1;
				FROM[i] = new FloatSetting("band" + n + "From", "Band " + n + " from", i == 0 ? "The first band starts at 0" : "Blocks; above the band before",
					BAND_FROM[i], 0, 10, 0.01F, "");
				BAND_TEXT_COLOR[i] = new RgbSetting("band" + n + "TextColor", "Band " + n + " text color", null, BAND_TEXT[i]);
				BAND_SHADOW_COLOR[i] = new RgbSetting("band" + n + "ShadowColor", "Band " + n + " shadow color", null, BAND_SHADOW[i]);
				BAND_BACKGROUND_COLOR[i] = new RgbSetting("band" + n + "BackgroundColor", "Band " + n + " background color", null, BAND_BACKGROUND[i]);
				BAND_BACKGROUND_OPACITY[i] = new IntSetting("band" + n + "BackgroundOpacity", "Band " + n + " background opacity", null, BAND_OPACITY[i],
					0, 100, 1, "%");
			}
		}

		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Reach Display",
				"Displays your latest hit distance on the HUD")
			.keywords("reach", "distance", "hit", "pvp", "player reach").icon(IconId.MOUSE).category(Category.COMBAT)
			.section("Appearance", SHADOW, BACKGROUND, TEXT_COLOR, SHADOW_COLOR, BACKGROUND_COLOR, BACKGROUND_OPACITY, SCALE, DISPLAY_MODE)
			.section("Behaviour", KEEP_LAST, RESET_AFTER)
			.section("Entity Filter", FILTER, FILTER_MODE, WHITELIST, BLACKLIST)
			.section("Distance Color", bandSettings()).drawnByMinecraft()
			.hud(HudPlacement.of(Anchor.TOP_LEFT, 4, 4)).needs(Requirement.WORLD)
			.note("Measured on your side as you attack: from your eyes to the nearest point of the target's hitbox. It never changes how far "
				+ "you can reach. Move it in the HUD editor.").build();

		private static com.flintclient.client.control.model.SettingDescriptor<?>[] bandSettings() {
			List<com.flintclient.client.control.model.SettingDescriptor<?>> out = new ArrayList<>();
			out.add(DISTANCE_COLOR);
			out.add(BANDS);
			for (int i = 0; i < MAX_BANDS; i++) {
				if (i > 0) {
					out.add(FROM[i]);
				}
				out.add(BAND_TEXT_COLOR[i]);
				out.add(BAND_SHADOW_COLOR[i]);
				out.add(BAND_BACKGROUND_COLOR[i]);
				out.add(BAND_BACKGROUND_OPACITY[i]);
			}
			return out.toArray(new com.flintclient.client.control.model.SettingDescriptor<?>[0]);
		}
	}

	public static final class ComboCounter {
		public static final String ID = "combo_counter";
		public static final BoxStyle STYLE = new BoxStyle(60, 13);
		public static final OptionSetting LABEL = labelMode("Prefix: Combo 4. Suffix: 4 hits.");
		public static final BoolSetting NO_COMBO = new BoolSetting("renderNoCombo", "Show “No combo”", "When you have no combo", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Combo Counter", "Display number of consecutive hits")
			.keywords("combo", "hits", "streak", "pvp").icon(IconId.MOUSE).category(Category.COMBAT)
			.section(STYLE.section("Style")).section("Combo Display", LABEL, NO_COMBO).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 95)).needs(Requirement.WORLD)
			.note("Counts your hits that the game shows landing (the target flashes hurt) without you being hit in between; it ends after "
				+ "2 seconds without a hit or when you take damage.").build();
	}

	public static final class Cooldowns {
		public static final String ID = "cooldowns";
		public static final OptionSetting FORMAT = new OptionSetting("format", "Show", "Seconds left or a bar", 0, "seconds", "Seconds", "bar",
			"Bar");
		public static final OptionSetting LAYOUT = new OptionSetting("layout", "Layout", null, 0, "vertical", "Vertical", "horizontal",
			"Horizontal");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Cooldowns", "Display items that are cooling down")
			.keywords("cooldown", "pearl", "shield", "timer").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("Display", FORMAT, LAYOUT)
			.hud(HudPlacement.of(Anchor.BOTTOM, 122, 4)).needs(Requirement.WORLD)
			.note("The cooldowns the server has given your items (ender pearls, shields, chorus fruit...)").build();
	}

	public static final class ItemCounter {
		public static final String ID = "item_counter";
		public static final OptionSetting LAYOUT = new OptionSetting("layout", "Display mode", null, 0, "horizontal", "Horizontal", "vertical",
			"Vertical");
		public static final BoolSetting SKIP_ZERO = new BoolSetting("skipZero", "Skip zero", "Hides items you have none of", true);
		public static final BoolSetting ARROWS = new BoolSetting("arrows", "Arrows", "All arrow kinds", true);
		public static final BoolSetting GOLDEN_APPLES = new BoolSetting("goldenApples", "Golden apples", "Both kinds", true);
		public static final BoolSetting PEARLS = new BoolSetting("enderPearls", "Ender pearls", true);
		public static final BoolSetting TOTEMS = new BoolSetting("totems", "Totems", false);
		public static final BoolSetting FIREWORKS = new BoolSetting("fireworks", "Firework rockets", false);
		public static final BoolSetting BLOCKS = new BoolSetting("blocks", "Building blocks", "Every placeable block, counted together", false);
		public static final TextSetting EXTRA = new TextSetting("extraItems", "More items",
			"Item ids, comma separated, like minecraft:cobweb", "", 200);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Item Counter", "Count items in your inventory")
			.keywords("items", "arrows", "count", "inventory").icon(IconId.SHIELD).category(Category.UTILITY)
			.section("Item Counter", LAYOUT, SKIP_ZERO).section("Items", ARROWS, GOLDEN_APPLES, PEARLS, TOTEMS, FIREWORKS, BLOCKS, EXTRA)
			.hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 141)).needs(Requirement.WORLD).build();
	}

	public static final class HorseStats {
		public static final String ID = "horse_stats";
		public static final BoxStyle STYLE = new BoxStyle(90, 13);
		public static final BoolSetting WHEN_LOOKING = new BoolSetting("crosshair", "Crosshair horse stats",
			"Also shows the horse you look at, not just the one you ride", true);
		public static final BoolSetting COLOR = new BoolSetting("colorStats", "Color horse stats", "Green for good, red for poor", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Horse Stats", "Add extra stats to horses")
			.keywords("horse", "speed", "jump", "mount").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Statistics", WHEN_LOOKING, COLOR).section(STYLE.section("Style")).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP, 0, 85)).needs(Requirement.WORLD)
			.note("Speed and jump come from the attributes the server sends for the horse; speed in blocks per second, jump in blocks.")
			.build();
	}

	public static final class TntCountdown {
		public static final String ID = "tnt_countdown";
		public static final IntSetting DECIMALS = new IntSetting("decimals", "Decimal points", null, 2, 0, 2, 1, "");
		public static final BoolSetting STATIC_COLOR = new BoolSetting("staticColor", "Static color", "Off: green to red as it runs out", false);
		public static final RgbSetting TIME_COLOR = new RgbSetting("timeColor", "Time color", "The timer's color with Static color on", 0xFFFFFF);
		public static final BoolSetting SHADOW = new BoolSetting("textShadow", "Text shadow", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "TNT Countdown", "Adds a timer to your TNT explosions")
			.keywords("tnt", "fuse", "explosion", "timer").icon(IconId.GAUGE).category(Category.WORLD)
			.section("General Options", DECIMALS).section("Style Options", STATIC_COLOR, TIME_COLOR, SHADOW).needs(Requirement.WORLD)
			.note("Each lit TNT shows the fuse the server synced for it, above the block").build();
	}

	/** The real time (and date), and the world's time of day. */
	public static final class Clock {
		public static final String ID = "clock";
		public static final BoxStyle STYLE = new BoxStyle(70, 13);
		public static final BoolSetting SHOW_TIME = new BoolSetting("showTime", "Show time", "Your computer's clock", true);
		public static final OptionSetting TIME_FORMAT = new OptionSetting("timeFormat", "Time format", null, 0, "24h", "24-hour (14:05)", "24hs",
			"24-hour with seconds", "12h", "12-hour (2:05 PM)", "12hs", "12-hour with seconds");
		public static final OptionSetting DATE_FORMAT = new OptionSetting("dateFormat", "Date format", null, 0, "none", "No date", "ymd",
			"2026/10/22", "dmy", "22/10/2026", "mdy", "10/22/2026", "text", "Thu 22 Oct");
		public static final BoolSetting SHOW_WORLD_TIME = new BoolSetting("showWorldTime", "Show world time", "The time of day in this world",
			false);
		public static final OptionSetting WORLD_TIME_FORMAT = new OptionSetting("worldTimeFormat", "World time format", null, 0, "24h", "24-hour",
			"12h", "12-hour");
		public static final RgbSetting WORLD_TIME_COLOR = new RgbSetting("worldTimeColor", "World time color", null, 0xFFE7A0);
		public static final OptionSetting ORIENTATION = new OptionSetting("orientation", "Orientation", "Each part on its own line, or one line",
			0, "vertical", "Stacked", "horizontal", "One line");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Time", "Show the real time and date")
			.keywords("clock", "time", "date", "real time", "hour").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Time", SHOW_TIME, TIME_FORMAT, DATE_FORMAT, SHOW_WORLD_TIME, WORLD_TIME_FORMAT, WORLD_TIME_COLOR, ORIENTATION)
			.section(STYLE.section("Style")).section(STYLE.detailSection()).style(STYLE).hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 96)).build();
	}

	/** The server's ticks per second, measured from its time updates. */
	public static final class Tps {
		public static final String ID = "tps";
		public static final BoxStyle STYLE = new BoxStyle(60, 13);
		public static final BoolSetting DYNAMIC_COLOR = new BoolSetting("dynamicColor", "Dynamic color",
			"Green at 19.5 and up, yellow from 15, red below", false);
		public static final BoolSetting REVERSED = new BoolSetting("reversed", "Reversed", "\"20 TPS\" instead of \"TPS 20\"", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "TPS", "Show how fast the server is ticking")
			.keywords("tps", "ticks", "server", "lag", "performance").icon(IconId.GAUGE).category(Category.PERFORMANCE)
			.section(STYLE.section("Style", DYNAMIC_COLOR, REVERSED)).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 111)).needs(Requirement.WORLD)
			.note("Measured from the game time the server sends about once a second; it appears a few seconds after joining.").build();
	}

	/** The address of the server you're on (and for how long). */
	public static final class ServerAddress {
		public static final String ID = "server_address";
		public static final BoxStyle STYLE = new BoxStyle(110, 13);
		public static final BoolSetting SHOW_PLAY_TIME = new BoolSetting("showPlayTime", "Show play time", "How long you've been on it", false);
		public static final BoolSetting HOURS = new BoolSetting("showHours", "Show hours", "Off: minutes keep counting past 59", true);
		public static final BoolSetting SECONDS = new BoolSetting("showSeconds", "Show seconds", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Server Address", "Show the address of the server you're on")
			.keywords("server", "address", "ip", "multiplayer", "host").icon(IconId.SIGNAL).category(Category.UTILITY)
			.section("General", SHOW_PLAY_TIME, HOURS, SECONDS).section(STYLE.section("Style")).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 126)).needs(Requirement.WORLD).build();
	}

	/** A stopwatch on the HUD, worked by its own keys. */
	public static final class Stopwatch {
		public static final String ID = "stopwatch";
		public static final BoxStyle STYLE = new BoxStyle(70, 13);
		public static final com.flintclient.client.control.model.KeySetting START_STOP = new com.flintclient.client.control.model.KeySetting(
			"startStopKey", "Start / stop key", "Starts from zero, or stops where it is", "");
		public static final com.flintclient.client.control.model.KeySetting PAUSE = new com.flintclient.client.control.model.KeySetting(
			"pauseKey", "Pause key", "Holds the running watch, or resumes it", "");
		public static final com.flintclient.client.control.model.KeySetting RESET = new com.flintclient.client.control.model.KeySetting(
			"resetKey", "Reset key", "Back to zero", "");
		public static final BoolSetting MILLIS = new BoolSetting("showMillis", "Show milliseconds", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Stopwatch", "Time anything with a few keys")
			.keywords("stopwatch", "timer", "time", "speedrun", "clock").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Stopwatch", START_STOP, PAUSE, RESET, MILLIS).section(STYLE.section("Style")).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP, 0, 100)).build();
	}
}
