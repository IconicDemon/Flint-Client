package com.flintclient.client.control.menu;

import com.flintclient.client.render.Icon;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Right Shift menu's module catalogue: one card per Flint module, in the owner's reference order (three columns, row
 * by row), with the card's name, icon, filter tags and NEW badge. Each entry is joined to its module in
 * {@link com.flintclient.client.control.model.ModuleRegistry} by {@link Entry#id()}, so a card's switch, favourite and
 * Options act on the real module (see {@code MenuCatalogTest}: every module has exactly one card).
 */
public final class MenuCatalog {
	/** The filter chips, in order. A module can be in several (the counts overlap); ALL holds every one. */
	public enum Category {
		ALL("ALL"),
		NEW("NEW"),
		HUD("HUD"),
		PVP("PVP"),
		VISUAL("VISUAL"),
		UTILITY("UTILITY");

		final String label;

		Category(String label) {
			this.label = label;
		}
	}

	/** One card: its module's id, the shown name, its icon, the categories it is listed under besides ALL, and the NEW badge. */
	public record Entry(String id, String name, Icon icon, Set<Category> tags, boolean isNew) {
		public boolean in(Category category) {
			return category == Category.ALL || category == Category.NEW && isNew || tags.contains(category);
		}
	}

	private static final Category HUD = Category.HUD;
	private static final Category PVP = Category.PVP;
	private static final Category VISUAL = Category.VISUAL;
	private static final Category UTILITY = Category.UTILITY;
	private static final boolean ON = false;
	private static final boolean OFF = false;

	/** Every entry, in display order. */
	public static final List<Entry> ENTRIES = List.of(
		entry("FPS", Icon.GAUGE, ON, true, false, HUD),
		entry("CPS", Icon.MOUSE, ON, false, false, HUD, PVP),
		entry("View Angles", Icon.VIEW_ANGLE, OFF, false, false, HUD),
		entry("Toggle Sneak/Sprint", Icon.RUNNER, ON, false, false, PVP, UTILITY),
		entry("Zoom", Icon.ZOOM_IN, ON, true, false, PVP, UTILITY),
		entry("Height Limit", Icon.STAIRS, OFF, false, true, UTILITY),
		entry("Armor Status", Icon.CHESTPLATE, ON, false, false, HUD, PVP),
		entry("Keystrokes", Icon.KEYSTROKES, ON, false, false, HUD, PVP),
		entry("Coordinates", Icon.PIN, OFF, false, false, HUD),
		entry("Day Counter", Icon.SUNRISE, OFF, false, false, HUD),
		entry("Crosshair", Icon.CROSSHAIR, OFF, false, true, HUD, VISUAL),
		entry("Attack Indicator", Icon.ATTACK, OFF, false, true, HUD, PVP),
		entry("Potion Effects", Icon.FLASK, ON, false, false, HUD, PVP),
		entry("Direction HUD", Icon.COMPASS, OFF, false, false, HUD),
		entry("Waypoints", Icon.FLAG, OFF, false, false, UTILITY),
		entry("Hit Color", Icon.HEART_CRACK, OFF, false, false, PVP, VISUAL),
		entry("Scoreboard", Icon.SCOREBOARD, ON, false, false, HUD),
		entry("Titles", Icon.TITLE_T, ON, false, false, HUD, VISUAL),
		entry("Item Counter", Icon.ITEMS, OFF, false, false, HUD),
		entry("Potion Counter", Icon.FLASKS, OFF, false, true, HUD, PVP),
		entry("Ping", Icon.SIGNAL, OFF, false, false, HUD, PVP),
		entry("Motion Blur", Icon.MOTION, OFF, false, false, VISUAL),
		entry("Pack Organizer", Icon.HIERARCHY, ON, false, false, UTILITY),
		entry("Chat", Icon.CHAT, ON, false, false, UTILITY),
		entry("Tab Editor", Icon.LIST, ON, false, false, UTILITY),
		entry("Nametags", Icon.TAG, ON, false, false, VISUAL),
		entry("Shulker Preview", Icon.SHULKER, ON, false, false, UTILITY),
		entry("Scrollable Tooltips", Icon.SCROLL_DOC, OFF, false, false, UTILITY),
		entry("Particle Changer", Icon.SPARKLES, OFF, false, false, VISUAL),
		entry("Nick Hider", Icon.MASK, OFF, false, false, UTILITY),
		entry("Cooldowns", Icon.HOURGLASS, OFF, false, false, HUD, PVP),
		entry("Playtime", Icon.PLAY_CIRCLE, OFF, false, true, HUD),
		entry("Memory Usage", Icon.MEMORY, OFF, false, false, HUD),
		entry("Combo Counter", Icon.TREND, OFF, false, false, HUD, PVP),
		entry("Reach Display", Icon.REACH, OFF, false, false, HUD, PVP),
		entry("Time Changer", Icon.MOON, OFF, false, false, VISUAL),
		entry("Saturation", Icon.DRUMSTICK, OFF, false, false, HUD),
		entry("Color Saturation", Icon.DROPLET, OFF, false, false, VISUAL),
		entry("Item Physics", Icon.APPLE, OFF, false, false, VISUAL),
		entry("TNT Countdown", Icon.BOMB, OFF, false, false),
		entry("3D Skins", Icon.PLAYER, OFF, false, false, VISUAL),
		entry("Momentum", Icon.MOMENTUM, OFF, false, false),
		entry("Block Outline", Icon.DASHED_SQUARE, OFF, false, false, VISUAL),
		entry("Screenshot Uploader", Icon.UPLOAD_FRAME, ON, false, false, UTILITY),
		entry("FOV Changer", Icon.FOV, OFF, false, false, VISUAL),
		entry("Fog Customizer", Icon.FOG, OFF, false, false, VISUAL),
		entry("2D Items", Icon.SHAPES, OFF, false, false, VISUAL),
		entry("Boss Bar", Icon.BOSS_BAR, ON, false, false, HUD, VISUAL),
		entry("Freelook", Icon.EYE, ON, false, false, UTILITY),
		entry("Menu Blur", Icon.NINE_DOTS, ON, false, false, VISUAL),
		entry("Hitbox", Icon.CUBE, OFF, false, false, PVP),
		entry("Lighting", Icon.BULB, ON, false, false, VISUAL),
		entry("Chunk Borders", Icon.CHUNKS, OFF, false, false, VISUAL),
		entry("Better Sounds", Icon.SPEAKER, OFF, false, false),
		entry("Hurt Cam", Icon.CAMERA, OFF, false, false, PVP, VISUAL),
		entry("Tier Tagger", Icon.SHIELD_STAR, OFF, false, true, PVP),
		entry("Damage Tint", Icon.BRUSH, OFF, false, false, PVP, VISUAL),
		entry("Horse Stats", Icon.HORSESHOE, ON, false, false),
		entry("Overlay", Icon.LAYERS, OFF, false, true, VISUAL),
		entry("Light Overlay", Icon.SUN, OFF, false, true, VISUAL),
		entry("Kill Sounds", Icon.SWORD, OFF, false, false, PVP),
		entry("Inventory", Icon.SLOTS, OFF, false, false, UTILITY),
		entry("GUI Scale", Icon.EXPAND, OFF, false, true),
		entry("Flint Music", Icon.MUSIC, OFF, false, true));

	private MenuCatalog() {
	}

	// The enabled and favourite columns of the reference are kept for reading order only: real state lives in the
	// layout (on/off) and the settings (favourites).
	private static Entry entry(String name, Icon icon, boolean enabled, boolean favorite, boolean isNew, Category... tags) {
		Set<Category> set = EnumSet.noneOf(Category.class);
		set.addAll(List.of(tags));
		String id = switch (name) {
			case "Armor Status" -> com.flintclient.client.control.model.ModuleId.ARMOR;
			case "Potion Effects" -> com.flintclient.client.control.model.ModuleId.EFFECTS;
			case "Direction HUD" -> com.flintclient.client.control.model.ModuleId.DIRECTION;
			default -> name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
		};
		return new Entry(id, name, icon, Set.copyOf(set), isNew);
	}

	/** The module behind {@code entry} (every card has one). */
	public static String moduleOf(Entry entry) {
		return entry.id();
	}

	/** The card icon of module {@code moduleId}, or {@link Icon#GAUGE} for one without a card. */
	public static Icon icon(String moduleId) {
		for (Entry entry : ENTRIES) {
			if (entry.id().equals(moduleId)) {
				return entry.icon();
			}
		}
		return Icon.GAUGE;
	}

	/** How many entries a chip shows. */
	public static int count(Category category) {
		int n = 0;
		for (Entry entry : ENTRIES) {
			if (entry.in(category)) {
				n++;
			}
		}
		return n;
	}

	/** Whether {@code entry} is shown under {@code category} for the search {@code query} (case-insensitive, by name). */
	public static boolean matches(Entry entry, Category category, String query) {
		if (!entry.in(category)) {
			return false;
		}
		String q = query.strip();
		return q.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT));
	}

	/**
	 * Whether {@code entry} shows under {@code category} for {@code query}: by its name (as {@link #matches}), or by one of
	 * its module's keywords, section titles or setting names, so "zoom key" or "smooth" finds the modules that have them.
	 */
	public static boolean matchesDeep(Entry entry, Category category, String query) {
		return matches(entry, category, query) || entry.in(category) && settingMatch(entry, query) != null;
	}

	/**
	 * Why {@code entry} matched {@code query} when its name doesn't contain it: {@code "Section · Setting"} (or the
	 * section's title, or {@code "Keyword: word"}); null when its name matches, the query is empty or nothing does. Runs
	 * when the search changes, not per frame.
	 */
	public static @org.jspecify.annotations.Nullable String settingMatch(Entry entry, String query) {
		String q = query.strip().toLowerCase(Locale.ROOT);
		if (q.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(q)) {
			return null;
		}
		com.flintclient.client.control.model.ModuleDescriptor module = com.flintclient.client.control.model.ModuleRegistry.find(entry.id());
		if (module == null) {
			return null;
		}
		for (com.flintclient.client.control.model.SettingSection section : module.sections()) {
			for (com.flintclient.client.control.model.SettingDescriptor<?> setting : section.settings()) {
				if (setting.label().toLowerCase(Locale.ROOT).contains(q)) {
					return section.title() + " · " + setting.label();
				}
			}
			if (section.title().toLowerCase(Locale.ROOT).contains(q)) {
				return section.title();
			}
		}
		for (String word : module.keywords()) {
			if (word.toLowerCase(Locale.ROOT).contains(q)) {
				return "Keyword: " + word;
			}
		}
		return null;
	}
}
