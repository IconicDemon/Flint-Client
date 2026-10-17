package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.IdListSetting;
import com.flintclient.client.control.model.IdValuesSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.SettingSection;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.TextSetting;

/** Chat, lists, tooltips, privacy and the other everyday helpers. */
public final class UtilityMods {
	private UtilityMods() {
	}

	public static final class Chat {
		public static final String ID = "chat";
		public static final BoolSetting TIMESTAMPS = new BoolSetting("timestamps", "Add timestamps to messages", false);
		public static final OptionSetting TIME_FORMAT = new OptionSetting("timeFormat", "Timestamp format", null, 0, "24h", "14:05", "12h",
			"2:05 PM", "seconds", "14:05:09");
		public static final RgbSetting TIME_COLOR = new RgbSetting("timeColor", "Timestamp color", null, 0x8A8A8A);
		public static final BoolSetting STACK = new BoolSetting("stack", "Stack repeated messages", "Shows a repeat as (2), (3)... instead", true);
		public static final BoolSetting CONSECUTIVE = new BoolSetting("consecutiveOnly", "Consecutive messages only",
			"Only stacks a message repeated right after itself", true);
		public static final OptionSetting HISTORY = new OptionSetting("history", "Scrollback", "How many messages chat keeps", 1, "100", "100",
			"500", "500", "1000", "1000", "5000", "5000");
		public static final BoolSetting KEEP = new BoolSetting("keepHistory", "Keep chat history",
			"Keeps chat when you leave a world or server (until you quit)", false);
		public static final BoolSetting MENTIONS = new BoolSetting("mentions", "Highlight mentions", "Messages with your name, in the color below",
			false);
		public static final RgbSetting MENTION_COLOR = new RgbSetting("mentionColor", "Mention color", null, 0xFFCC4D);
		public static final BoolSetting NO_BRACKETS = new BoolSetting("removeAngleBrackets", "Remove angle brackets",
			"Name: message instead of <Name> message", false);
		public static final RgbSetting BACKGROUND = new RgbSetting("backgroundColor", "Background color",
			"Its opacity is Minecraft's Text Background Opacity", 0x000000);
		public static final KeySetting TOGGLE_KEY = new KeySetting("toggleKey", "Toggle chat", "Hides or shows chat on screen (it still opens with T)",
			KeySetting.NONE);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Chat", "Tweak your chat display")
			.keywords("chat", "timestamps", "history", "stack").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", NO_BRACKETS, TOGGLE_KEY, BACKGROUND)
			.section("Message Timestamps", TIMESTAMPS, TIME_FORMAT, TIME_COLOR).section("Stack Messages", STACK, CONSECUTIVE)
			.section("Chat Settings", HISTORY, KEEP).section("Mentions", MENTIONS, MENTION_COLOR)
			.note("Changes only how chat shows on your screen: what you send, and Minecraft's chat signing, its secure-chat indicators and "
				+ "reporting stay as they are.")
			.partial("Chat tabs, calculator, avatars, search, copy on click, smooth animation and moving the chat aren't built yet. Translation "
				+ "needs a translation service you've agreed to, so it isn't offered.").build();
	}

	public static final class TabEditor {
		public static final String ID = "tab_editor";
		public static final BoolSetting HIDE_HEADER = new BoolSetting("hideHeader", "Disable header", false);
		public static final BoolSetting HIDE_FOOTER = new BoolSetting("hideFooter", "Disable footer", false);
		public static final BoolSetting HIDE_HEADS = new BoolSetting("hideHeads", "Disable player heads", false);
		public static final BoolSetting PING_NUMBER = new BoolSetting("pingNumber", "Display ping as number", true);
		public static final BoolSetting PING_COLOR = new BoolSetting("pingColor", "Color ping by latency", "Green to red; off: grey", true);
		public static final BoolSetting HOIST = new BoolSetting("hoistSelf", "Hoist self", "You first in the list", false);
		public static final BoolSetting EXTEND = new BoolSetting("extendLimit", "Extend tablist player limit",
			"Up to 160 players instead of Minecraft's 80", false);
		public static final BoolSetting TOGGLE = new BoolSetting("toggle", "Toggle tablist", "A tap of the key shows it until the next tap", false);
		public static final RgbSetting BACKGROUND = new RgbSetting("backgroundColor", "Background color", null, 0x000000);
		public static final IntSetting BACKGROUND_OPACITY = new IntSetting("backgroundOpacity", "Background opacity", null, 50, 0, 100, 5, "%");
		public static final RgbSetting ROW = new RgbSetting("rowColor", "Row color", null, 0xFFFFFF);
		public static final IntSetting ROW_OPACITY = new IntSetting("rowOpacity", "Row opacity", null, 13, 0, 100, 1, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Tab Editor", "Tweak tablist settings")
			.keywords("tab", "player list", "ping", "header").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", HIDE_HEADER, HIDE_FOOTER, HIDE_HEADS, PING_NUMBER, PING_COLOR, HOIST, EXTEND, TOGGLE)
			.section("Color", BACKGROUND, BACKGROUND_OPACITY, ROW, ROW_OPACITY)
			.note("Players, their names, teams and order are the server's; only how the list is drawn changes (and you first, when asked).")
			.build();
	}

	public static final class Scoreboard {
		public static final String ID = "scoreboard";
		public static final BoolSetting HIDE = new BoolSetting("hide", "Hide scoreboard", false);
		public static final BoolSetting NUMBERS = new BoolSetting("showNumbers", "Show numbers", "The red score numbers", false);
		public static final IntSetting OPACITY = new IntSetting("opacity", "Background opacity", null, 30, 0, 100, 5, "%");
		public static final RgbSetting BACKGROUND_COLOR = new RgbSetting("backgroundColor", "Background color", null, 0x000000);
		public static final RgbSetting TITLE_BACKGROUND = new RgbSetting("titleBackground", "Title background", null, 0x000000);
		public static final BoolSetting BORDER = new BoolSetting("border", "Border", false);
		public static final RgbSetting BORDER_COLOR = new RgbSetting("borderColor", "Border color", null, 0x000000);
		public static final RgbSetting TEXT_COLOR = new RgbSetting("textColor", "Text color", "Lines the server didn't color", 0xFFFFFF);
		public static final BoolSetting SHADOW = new BoolSetting("textShadow", "Text shadow", false);
		public static final IntSetting SCALE = new IntSetting("scale", "Scale", null, 100, 50, 200, 10, "%");
		public static final IntSetting X_OFFSET = new IntSetting("xOffset", "Move left", "From the right edge, in GUI units", 0, 0, 400, 2, "");
		public static final IntSetting Y_OFFSET = new IntSetting("yOffset", "Move down", "From Minecraft's spot; negative moves it up", 0, -200,
			200, 2, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Scoreboard", "Customize scoreboard")
			.keywords("scoreboard", "sidebar", "numbers", "server").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Scoreboard", HIDE, NUMBERS, SHADOW).section("Style", OPACITY, BACKGROUND_COLOR, TITLE_BACKGROUND, BORDER, BORDER_COLOR,
				TEXT_COLOR).section("Position", SCALE, X_OFFSET, Y_OFFSET)
			.note("The server's lines and their order are kept exactly; only how and where they're drawn changes.")
			.partial("Dragging it in the HUD editor isn't built yet; move it with the position settings").build();
	}

	public static final class Titles {
		public static final String ID = "titles";
		public static final BoolSetting SHOW = new BoolSetting("show", "Title display", "Off: titles and subtitles aren't drawn", true);
		public static final BoolSetting PRINT = new BoolSetting("printTitles", "Print title & subtitle to chat", false);
		public static final BoolSetting PRINT_ACTIONBAR = new BoolSetting("printActionbar", "Print actionbar title to chat",
			"Repeats of the same text aren't printed again", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Titles", "Make adjustments to the title display")
			.keywords("title", "subtitle", "actionbar", "chat").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Title Tweaker", SHOW).section("Print Titles to Chat", PRINT, PRINT_ACTIONBAR)
			.note("Printed lines go into your own chat only, marked as a title.").build();
	}

	public static final class BossBar {
		public static final String ID = "boss_bar";
		public static final BoolSetting HIDE = new BoolSetting("hide", "Hide boss bars", false);
		public static final BoolSetting SHADOW = new BoolSetting("textShadow", "Text shadow", true);
		public static final BoolSetting OVERWRITE = new BoolSetting("overwriteColor", "Overwrite text color", false);
		public static final RgbSetting COLOR = new RgbSetting("color", "Text color", null, 0xFFFFFF);
		public static final IntSetting SCALE = new IntSetting("scale", "Scale", null, 100, 50, 200, 10, "%");
		public static final IntSetting X_OFFSET = new IntSetting("xOffset", "Move right", "From the centre; negative moves it left", 0, -300, 300, 2,
			"");
		public static final IntSetting Y_OFFSET = new IntSetting("yOffset", "Move down", "From the top, in GUI units", 0, 0, 300, 2, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Boss Bar", "Customize your bossbar")
			.keywords("boss", "bar", "wither", "dragon").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Boss Bar", HIDE, SHADOW, OVERWRITE, COLOR).section("Position", SCALE, X_OFFSET, Y_OFFSET)
			.note("Every bar the server sends is drawn, with its own progress and color; several stack as Minecraft stacks them.")
			.partial("Dragging it in the HUD editor isn't built yet; move it with the position settings").build();
	}

	public static final class NickHider {
		public static final String ID = "nick_hider";
		public static final BoolSetting HIDE_NICK = new BoolSetting("hideNick", "Hide your nick", "In your nametag and the tab list", false);
		public static final TextSetting FAKE_NICK = new TextSetting("fakeNick", "Your fake nick", null, "You", 16);
		public static final BoolSetting HIDE_OTHERS = new BoolSetting("hideOthers", "Hide other names", "Other players show as Player", false);
		public static final BoolSetting HIDE_SKIN = new BoolSetting("hideSkin", "Hide your skin", "You see a default skin on yourself", false);
		public static final BoolSetting HIDE_OTHER_SKINS = new BoolSetting("hideOtherSkins", "Hide other skins", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Nick Hider", "Hide your nick and skin")
			.keywords("nick", "name", "privacy", "streamer").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Name Options", HIDE_NICK, FAKE_NICK, HIDE_OTHERS).section("Skin Options", HIDE_SKIN, HIDE_OTHER_SKINS)
			.note("Only your screen changes: nametags, the tab list and chat messages. The server and other players still see your real "
				+ "name and skin, so this is for recordings and streams, not anonymity. Chat signing and reporting are untouched.").build();
	}

	public static final class Screenshots {
		public static final String ID = "screenshot_uploader";
		public static final OptionSetting AFTER = new OptionSetting("afterScreenshot", "After screenshot", "Once it's saved", 0, "none", "None",
			"copy_path", "Copy path", "open", "Open file", "folder", "Open folder");
		public static final BoolSetting ON_DEATH = new BoolSetting("onDeath", "Screenshot on death", false);
		public static final BoolSetting JPEG = new BoolSetting("jpeg", "Save as JPEG", "Smaller files; the PNG is replaced by a .jpg", false);
		public static final BoolSetting PREVIEW = new BoolSetting("preview", "Screenshot preview", true);
		public static final IntSetting DURATION = new IntSetting("duration", "Preview duration", null, 3, 1, 10, 1, " s");
		public static final IntSetting SIZE = new IntSetting("size", "Scale", "Share of the screen width", 25, 15, 40, 5, "%");
		public static final IntSetting ANIMATION = new IntSetting("animationMs", "Animation duration", "0: no slide", 250, 0, 1000, 50, " ms");
		public static final OptionSetting CORNER = new OptionSetting("corner", "Corner", null, 0, "bottom_right", "Bottom right", "top_right",
			"Top right", "bottom_left", "Bottom left", "top_left", "Top left");
		public static final IntSetting X_OFFSET = new IntSetting("xOffset", "X offset", "From the corner", 0, 0, 200, 2, "");
		public static final IntSetting Y_OFFSET = new IntSetting("yOffset", "Y offset", "From the corner", 0, 0, 200, 2, "");
		public static final RgbSetting BORDER = new RgbSetting("borderColor", "Border color", null, 0xFFFFFF);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Screenshot Uploader", "Extra screenshot features")
			.keywords("screenshot", "f2", "preview", "upload", "jpeg").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Screenshot", AFTER, ON_DEATH, JPEG).section("Screenshot Preview", PREVIEW, DURATION, SIZE, ANIMATION, CORNER, X_OFFSET, Y_OFFSET,
				BORDER)
			.note("Screenshots stay on this computer, in Minecraft's screenshots folder.")
			.partial("Uploading isn't offered: Flint has no upload service, and it would never upload without you choosing one. Copy and "
				+ "Open link need an upload, so the local Copy path and Open file are offered instead. Chat screenshots aren't built yet")
			.build();
	}

	public static final class Waypoints {
		public static final String ID = "waypoints";
		public static final BoolSetting MARKERS = new BoolSetting("markers", "Show markers", "Each waypoint's name and distance where it is", true);
		public static final BoolSetting SHOW_NAME = new BoolSetting("showName", "Show name", true);
		public static final BoolSetting SHOW_DISTANCE = new BoolSetting("showDistance", "Show distance", true);
		public static final IntSetting MAX_DISTANCE = new IntSetting("maxDistance", "Hide beyond", "In blocks; 0 shows them at any distance", 0, 0, 5000,
			100, "");
		public static final KeySetting CREATE_KEY = new KeySetting("createKey", "Create waypoint", "Marks where you stand", "key.keyboard.b");
		public static final KeySetting TOGGLE_KEY = new KeySetting("toggleKey", "Toggle waypoints", "Shows or hides every marker",
			KeySetting.NONE);
		public static final BoolSetting ON_DEATH = new BoolSetting("onDeath", "Create waypoint on death", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Waypoints", "Mark places and find your way back")
			.keywords("waypoint", "marker", "home", "death").icon(IconId.PIN).category(Category.WORLD)
			.section("General", MARKERS, SHOW_NAME, SHOW_DISTANCE, MAX_DISTANCE, ON_DEATH).section("Key Settings", CREATE_KEY, TOGGLE_KEY)
			.needs(Requirement.WORLD)
			.note("Waypoints are kept per world or server and per dimension, on this computer only, and never sent anywhere. Edit them in "
				+ "the menu's Waypoints tab: each has its own name and distance labels, a beam and a highlighted block, a group and a "
				+ "dimension. A Nether waypoint shows only in the Nether: its coordinates are never converted.").build();
	}

	public static final class ShulkerPreview {
		public static final String ID = "shulker_preview";
		public static final OptionSetting SHOW = new OptionSetting("show", "Show contents", null, 0, "always", "Always", "shift", "Holding Shift");
		public static final BoolSetting HIDE_LIST = new BoolSetting("hideList", "Hide the text list", "Minecraft's \u201C3x Stone\u201D lines under it",
			true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Shulker Preview", "See the contents of shulker boxes")
			.keywords("shulker", "container", "tooltip", "preview").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Container Tooltips", SHOW, HIDE_LIST)
			.note("A grid of the box's items in its tooltip, from the contents the game already sent with the item.").build();
	}

	public static final class ScrollableTooltips {
		public static final String ID = "scrollable_tooltips";
		public static final BoolSetting SNAP_START = new BoolSetting("snapToStart", "Snap to start",
			"Each new tooltip opens at its first line; off: it keeps where you scrolled", true);
		public static final KeySetting VERTICAL_KEY = new KeySetting("verticalKey", "Vertical scrolling key",
			"None: the mouse wheel alone scrolls a tooltip that doesn't fit", KeySetting.NONE);
		public static final KeySetting HORIZONTAL_KEY = new KeySetting("horizontalKey", "Horizontal scrolling key",
			"Hold it while turning the wheel to move a wide tooltip sideways", "key.keyboard.left.shift");
		public static final KeySetting SCALE_KEY = new KeySetting("scaleKey", "Scale key",
			"Hold it while turning the wheel to make the tooltip smaller or larger", "key.keyboard.left.control");
		public static final IntSetting DEFAULT_SCALE = new IntSetting("defaultScale", "Default tooltip scale", null, 100, 25, 200, 5, "%");
		public static final BoolSetting FREE = new BoolSetting("freeScrolling", "Free scrolling mode",
			"Any tooltip can be moved, even one that fits", false);
		public static final BoolSetting LINE_SHIFT = new BoolSetting("lineShift", "Line shift mode",
			"Moves a whole line per notch at once instead of gliding", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Scrollable Tooltips",
				"Read tooltips longer than your window")
			.keywords("tooltip", "scroll", "long", "items", "lore").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Scrolling", SNAP_START, VERTICAL_KEY, HORIZONTAL_KEY, FREE, LINE_SHIFT).section("Scale", SCALE_KEY, DEFAULT_SCALE)
			.note("Turn the mouse wheel while a tooltip is showing. The wheel only moves tooltips that don't fit (unless free scrolling is "
				+ "on), so scrolling lists works as before.").build();
	}

	public static final class PackOrganizer {
		public static final String ID = "pack_organizer";
		public static final BoolSetting SHOW_INCOMPATIBLE = new BoolSetting("showIncompatible", "Show incompatible packs",
			"Packs made for another Minecraft version", true);
		public static final OptionSetting SORT = new OptionSetting("sort", "Sort", null, 0, "name", "Name", "on_first", "On first");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Pack Organizer", "Organize your resource packs into folders")
			.keywords("resource packs", "folders", "packs", "organize", "textures").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Packs", SHOW_INCOMPATIBLE, SORT)
			.note("Folders are Flint's own labels: your pack files are never moved, renamed or deleted, and a folder never changes which "
				+ "packs are on or their order. Turning packs on or off waits for Apply, which reloads resources as Minecraft's own "
				+ "Resource Packs screen does.").build();
	}

	public static final class Inventory {
		public static final String ID = "inventory";
		// Search
		public static final BoolSetting SEARCH = new BoolSetting("search", "Inventory search", "A search box above chests and your inventory",
			true);
		public static final OptionSetting HIGHLIGHT = new OptionSetting("highlight", "Highlight", "How matching items stand out", 0, "dim",
			"Dim others", "outline", "Outline matches");
		public static final BoolSetting SEARCH_LORE = new BoolSetting("searchLore", "Always search lore", "Also matches an item's lore lines",
			false);
		public static final KeySetting SEARCH_KEY = new KeySetting("searchKey", "Open with keybind", "Puts the cursor in the search box",
			KeySetting.NONE);
		// Inventory HUD
		public static final BoolSetting HUD = new BoolSetting("hud", "Inventory HUD", "Your 27 inventory slots on screen (move it in the HUD "
			+ "editor)", false);
		public static final BoolSetting HUD_EMPTY = new BoolSetting("hudShowEmpty", "Show empty", "Also when your inventory is empty", false);
		public static final BoolSetting HUD_BACKGROUND = new BoolSetting("hudBackground", "Show background", true);
		// Slot locking
		public static final BoolSetting LOCKING = new BoolSetting("slotLocking", "Slot locking", "Locked slots can't be clicked, moved or dropped",
			true);
		public static final KeySetting LOCK_KEY = new KeySetting("lockKey", "Lock key", "Hold the pointer over a slot of your inventory and press "
			+ "it to lock or unlock", "key.keyboard.l");
		public static final RgbSetting LOCK_COLOR = new RgbSetting("lockColor", "Icon color", null, 0xFF8139);
		public static final IdListSetting LOCKED = new IdListSetting("lockedSlots", "Locked slots", null, IdKind.SLOT, java.util.List.of());
		// Drop protection
		public static final BoolSetting PROTECT = new BoolSetting("dropProtection", "Item drop protection",
			"The items below can't be dropped with the drop key or thrown out of your inventory", true);
		public static final IdListSetting PROTECTED = new IdListSetting("protectedItems", "Protected items", null, IdKind.ITEM, java.util.List.of(
			"minecraft:totem_of_undying", "minecraft:elytra", "minecraft:netherite_sword", "minecraft:enchanted_golden_apple"));
		public static final BoolSetting PROTECT_CONTAINERS = new BoolSetting("protectInContainers", "In other containers too",
			"Also stops throwing them out of chests and other screens", false);
		// Hotbar keys
		public static final BoolSetting KEYS = new BoolSetting("hotbarKeys", "Hotbar key overlay", "The key for each hotbar slot on the slot",
			false);
		public static final OptionSetting KEYS_POSITION = new OptionSetting("hotbarKeysPosition", "Position", null, 0, "top_left", "Top left",
			"top_right", "Top right", "bottom_left", "Bottom left", "bottom_right", "Bottom right");
		public static final RgbSetting KEYS_COLOR = new RgbSetting("hotbarKeysColor", "Color", null, 0xFFFFFF);
		public static final BoolSetting KEYS_SMALL = new BoolSetting("hotbarKeysSmall", "Small text", true);
		public static final BoolSetting KEYS_LOWERCASE = new BoolSetting("hotbarKeysLowercase", "Lowercase", false);
		public static final BoolSetting KEYS_SHADOW = new BoolSetting("hotbarKeysShadow", "Text shadow", true);
		public static final BoolSetting KEYS_HIDE_EMPTY = new BoolSetting("hotbarKeysHideEmpty", "Hide when empty", false);
		public static final BoolSetting KEYS_HIDE_SELECTED = new BoolSetting("hotbarKeysHideSelected", "Hide when selected", false);
		// Cursor
		public static final BoolSetting KEEP_CURSOR = new BoolSetting("keepCursor", "Don't reset cursor",
			"Opening a screen right after closing one keeps the pointer where it was", false);
		public static final IntSetting CURSOR_MS = new IntSetting("cursorResetMs", "Time until reset", "How soon after closing a screen",
			1000, 100, 5000, 100, " ms");
		public static final BoolSetting CURSOR_CONTAINERS = new BoolSetting("cursorContainersOnly", "Only in chests",
			"Only between chests and other containers", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Inventory", "Inventory screen tweaks")
			.keywords("inventory", "items", "search", "lock", "slots", "drop").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Inventory Search", SEARCH, HIGHLIGHT, SEARCH_LORE, SEARCH_KEY).section("Inventory HUD", HUD, HUD_EMPTY, HUD_BACKGROUND)
			.section("Slot Locking", LOCKING, LOCK_KEY, LOCK_COLOR, LOCKED).section("Item Drop Protection", PROTECT, PROTECTED, PROTECT_CONTAINERS)
			.section("Hotbar Key Overlay", KEYS, KEYS_POSITION, KEYS_COLOR, KEYS_SMALL, KEYS_LOWERCASE, KEYS_SHADOW, KEYS_HIDE_EMPTY,
				KEYS_HIDE_SELECTED)
			.section("Don't Reset Cursor", KEEP_CURSOR, CURSOR_MS, CURSOR_CONTAINERS)
			.hud(com.flintclient.client.control.hud.HudPlacement.of(com.flintclient.client.control.hud.Anchor.BOTTOM_RIGHT, 4, 130))
			.needs(Requirement.WORLD)
			.note("Everything here acts on your side only and never moves items for you. Locks and drop protection stop your own clicks and "
				+ "keys; a server, a plugin or dying can still move or drop items. Not in the creative inventory.")
			.partial("Slot binding, enchantment summaries on slots, container animations and the hotbar customizer aren't built yet").build();
	}

	public static final class BetterSounds {
		public static final String ID = "better_sounds";
		private static final String[] NAMES = {"Item", "Entity", "Ambient", "Music", "UI", "Music Disc", "Enchant", "Weather", "Block",
			"Particle", "Event", "Other"};
		/** One volume per {@code SoundMixer.CATEGORIES} entry, then "other". */
		public static final IntSetting[] CATEGORY = categories();
		public static final IdValuesSetting PER_SOUND = new IdValuesSetting("perSound", "Single sounds",
			"Any sound's own volume, on top of its category; search, play and set each one", IdKind.SOUND, 100, 0, 100, 5, "%");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Better Sounds", "Fine-tune the volume of every sound")
			.keywords("sound", "volume", "mute", "audio", "music").icon(IconId.GAUGE).category(Category.UTILITY)
			.section(SettingSection.of("Categories", CATEGORY)).section("Single Sounds", PER_SOUND)
			.note("Lowers or mutes sounds on your side only, by kind (the part before the first dot of the sound's name) and one by one. "
				+ "Minecraft's own volume sliders still apply. Reverb and occlusion aren't offered.").build();

		private static IntSetting[] categories() {
			IntSetting[] out = new IntSetting[NAMES.length];
			for (int i = 0; i < NAMES.length; i++) {
				String key = i < com.flintclient.client.control.feature.SoundMixer.CATEGORIES.length
					? com.flintclient.client.control.feature.SoundMixer.CATEGORIES[i] : "other";
				out[i] = new IntSetting("category." + key, NAMES[i], null, 100, 0, 100, 5, "%");
			}
			return out;
		}
	}

	public static final class FlintMusic {
		public static final String ID = "flint_music";
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Flint Music", "Your music, without leaving the game")
			.keywords("music", "spotify", "player", "songs").icon(IconId.GAUGE).category(Category.UTILITY)
			.blocked("Spotify's Developer Policy doesn't allow its music in a game (“Do not create a game”, III.2) or a copy of its "
				+ "player (III.11), so Flint won't connect to Spotify. A player for your own music files is planned instead.").build();
	}

	static ModuleDescriptor notBuilt(String id, String name, String description, String why, String... keywords) {
		return ModuleDescriptor.builder(id, name, description).keywords(keywords).category(Category.UTILITY).notBuilt(why).build();
	}
}
