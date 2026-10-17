package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.BoxStyle;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FpsSizing;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.TextSetting;

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

	public static final class Playtime {
		public static final String ID = "playtime";
		public static final BoxStyle STYLE = new BoxStyle(80, 13);
		public static final OptionSetting TIME_TYPE = new OptionSetting("timeType", "Time type",
			"Since Launch counts from game start; In-Game from joining this world", 0, "launch", "Since Launch", "world", "In-Game");
		public static final BoolSetting SUFFIX = new BoolSetting("showSuffix", "Show time suffix", "1h 02m 09s instead of 01:02:09", false);
		public static final BoolSetting HOURS = new BoolSetting("showHours", "Show hours", "Off: minutes keep counting past 59", true);
		public static final BoolSetting SECONDS = new BoolSetting("showSeconds", "Show seconds", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Playtime", "Show time game open or in-game")
			.keywords("time", "session", "clock", "played").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", TIME_TYPE, SUFFIX, HOURS, SECONDS).section(STYLE.section("Style")).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 34)).build();
	}

	public static final class DayCounter {
		public static final String ID = "day_counter";
		public static final BoxStyle STYLE = new BoxStyle(56, 13);
		public static final OptionSetting LABEL = labelMode("Prefix: Day 12. Suffix: 12 days.");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Day Counter", "Display the current in-game day")
			.keywords("day", "days", "time", "world").icon(IconId.GAUGE).category(Category.WORLD)
			.section(STYLE.section("Style")).section("Day", LABEL).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP_RIGHT, 4, 49)).needs(Requirement.WORLD)
			.note("The world's day as Minecraft counts it (the overworld clock divided by 24000)").build();
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
		public static final OptionSetting TOOLTIP = new OptionSetting("tooltipFood", "Tooltip food values",
			"Hunger and saturation a food gives, in its tooltip", 1, "never", "Never", "shift", "Shift Only", "always", "Always");
		public static final BoolSetting OVERLAY = new BoolSetting("overlay", "Saturation overlay", "Your saturation drawn over the hunger bar",
			true);
		public static final RgbSetting COLOR = new RgbSetting("color", "Saturation color", null, 0xFFC93C);
		public static final BoolSetting FOOD_GAIN = new BoolSetting("foodGain", "Food gain overlay",
			"While you hold food: the hunger it would fill, blinking", true);
		public static final BoolSetting SATURATION_GAIN = new BoolSetting("saturationGain", "Saturation gain overlay",
			"While you hold food: the saturation it would add, blinking", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Saturation", "Adds saturation visualization to food")
			.keywords("food", "hunger", "saturation", "eat").icon(IconId.FLASK).category(Category.UTILITY)
			.section("General", HUD, SHOW_FOOD, SHOW_LABEL, TOOLTIP).section("Hunger Bar", OVERLAY, COLOR, FOOD_GAIN, SATURATION_GAIN)
			.section(STYLE.section("Style")).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP, 0, 55)).needs(Requirement.WORLD)
			.note("The server sends your saturation with every health update, so these are the real values. A food's values are the ones "
				+ "the game knows for it; an effect it gives (a golden apple's) isn't counted.").build();
	}

	public static final class PotionCounter {
		public static final String ID = "potion_counter";
		public static final BoxStyle STYLE = new BoxStyle(60, 13);
		public static final OptionSetting COUNT = new OptionSetting("count", "Count", null, 0, "splash_healing", "Splash healing", "healing",
			"All healing", "all", "All potions");
		public static final OptionSetting LABEL = labelMode("Prefix: Pots 12. Suffix: 12 pots.");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Potion Counter", "Count the potions in your inventory")
			.keywords("pots", "potions", "healing", "pvp").icon(IconId.FLASK).category(Category.COMBAT)
			.section(STYLE.section("Style")).section("Potions", COUNT, LABEL).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.TOP, 0, 70)).needs(Requirement.WORLD).build();
	}

	public static final class ReachDisplay {
		public static final String ID = "reach_display";
		public static final BoxStyle STYLE = new BoxStyle(60, 13);
		public static final IntSetting DIGITS = new IntSetting("digits", "Digits", "Decimal places", 1, 0, 3, 1, "");
		public static final OptionSetting LABEL = labelMode("Prefix: Reach 2.9. Suffix: 2.9 blocks.");
		public static final BoolSetting NO_HIT = new BoolSetting("renderNoHit", "Show “No hit”", "When nothing was hit lately", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Reach Display", "Shows the distance when hitting a player")
			.keywords("reach", "distance", "hit", "pvp").icon(IconId.MOUSE).category(Category.COMBAT)
			.section(STYLE.section("Style")).section("Reach Display", DIGITS, LABEL, NO_HIT).section(STYLE.detailSection()).style(STYLE)
			.hud(HudPlacement.of(Anchor.BOTTOM_RIGHT, 4, 80)).needs(Requirement.WORLD)
			.note("Measured on your side when you attack: from your eyes to the nearest point of the target's hitbox. It never changes "
				+ "how far you can reach.").build();
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
			.hud(HudPlacement.of(Anchor.TOP_LEFT, 4, 99)).needs(Requirement.WORLD)
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
			.hud(HudPlacement.of(Anchor.TOP_LEFT, 4, 114)).needs(Requirement.WORLD).build();
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
		public static final BoolSetting SHADOW = new BoolSetting("textShadow", "Text shadow", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "TNT Countdown", "Adds a timer to your TNT explosions")
			.keywords("tnt", "fuse", "explosion", "timer").icon(IconId.GAUGE).category(Category.WORLD)
			.section("General Options", DECIMALS).section("Style Options", STATIC_COLOR, SHADOW).needs(Requirement.WORLD)
			.note("Each lit TNT shows the fuse the server synced for it, above the block").build();
	}
}
