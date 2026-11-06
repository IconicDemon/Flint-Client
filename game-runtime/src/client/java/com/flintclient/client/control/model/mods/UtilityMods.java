package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
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
			"Its opacity is Minecraft's Text Background Opacity; Transparent draws none", 0x000000, true);
		public static final KeySetting TOGGLE_KEY = new KeySetting("toggleKey", "Toggle chat", "Hides or shows chat on screen (it still opens with T)",
			KeySetting.NONE);
		public static final BoolSetting SEARCH = new BoolSetting("chatSearch", "Chat search",
			"A search box above the chat input: only messages with what you type show", true);
		public static final KeySetting SEARCH_KEY = new KeySetting("focusSearchKey", "Focus search key", "Pressed with Ctrl while chat is open",
			"key.keyboard.f").inChat();
		public static final BoolSetting SMOOTH = new BoolSetting("smoothChat", "Animate new messages", "They slide up and fade in", true);
		public static final IntSetting SMOOTH_TIME = new IntSetting("speed", "Animation length", null, 200, 10, 500, 10, "ms");
		public static final BoolSetting SMOOTH_INPUT = new BoolSetting("smoothInputBar", "Smooth input bar", "The chat input slides up as chat opens",
			true);
		public static final IntSetting INPUT_TIME = new IntSetting("smoothInputDuration", "Input bar animation length", null, 170, 10, 500, 10, "ms");
		public static final IntSetting INPUT_OFFSET = new IntSetting("smoothInputOffset", "Input bar slide distance", null, 8, 1, 32, 1, "px");
		public static final OptionSetting INPUT_EASING = new OptionSetting("inputEasing", "Input bar easing", "Bounce overshoots a little, then settles",
			1, "bounce", "Bounce", "smooth", "Smooth");
		public static final BoolSetting TABS = new BoolSetting("chatTabs", "Chat tabs",
			"All, Chat, Server and Private above the chat input, and a tab of your own", true);
		public static final BoolSetting TAB_OWN = new BoolSetting("chatTabShowOwnMessage", "Show own messages", "What you send shows in every tab",
			true);
		public static final KeySetting TAB_KEY = new KeySetting("chatTabMenuKey", "Next tab key",
			"Switches tabs while chat is open: pick one you don't type with, such as F6", KeySetting.NONE).inChat();
		public static final TextSetting TAB_NAME = new TextSetting("customTabName", "Your tab's name", "Leave it empty for no tab of your own", "",
			16);
		public static final TextSetting TAB_WORDS = new TextSetting("customTabWords", "Your tab's words",
			"Messages with any of these words show in it; separate them with commas", "", 120);
		public static final BoolSetting CALC = new BoolSetting("chatCalc", "Chat calculator",
			"Type =2+2*3 and press Enter: the answer shows in your chat and nothing is sent", true);
		public static final IntSetting CALC_PLACES = new IntSetting("chatCalcPlaces", "Decimal places", null, 3, 1, 16, 1, "");
		public static final OptionSetting CALC_INPUT = chatColor("chatCalcInput", "Sum color", 11);
		public static final OptionSetting CALC_OUTPUT = chatColor("chatCalcOutput", "Answer color", 7);
		public static final OptionSetting CALC_ERROR = chatColor("chatCalcError", "Error color", 12);
		public static final BoolSetting AVATARS = new BoolSetting("displayChatAvatars", "Player heads", "The sender's head before their messages",
			true);
		public static final BoolSetting ROUND_AVATARS = new BoolSetting("circularAvatars", "Round heads", false);
		public static final BoolSetting ALIGN = new BoolSetting("alignChat", "Offset non-player text",
			"All text lines up: lines without a head start where the others' text does (Before line only)", true);
		// Chat Heads (dzwdz, MPL-2.0)
		public static final OptionSetting HEAD_POSITION = new OptionSetting("renderPosition", "Render position",
			"Before name: the head sits just before the sender's name. Before line: at the start of the line", 1, "before_line", "Before Line",
			"before_name", "Before Name");
		public static final IntSetting THREE_D = new IntSetting("threeDeeNess", "3Dness",
			"Draws the hat layer slightly bigger, so heads look less flat", 0, 0, 100, 1, "%");
		public static final OptionSetting SENDER_DETECTION = new OptionSetting("senderDetection", "Sender detection",
			"Servers usually say who sent a message; many don't, or fake it, and then the name is guessed from the message. Heuristic "
				+ "only is for servers that send fake senders", 1, "uuid_only", "UUID only", "uuid_and_heuristic", "UUID and heuristic",
			"heuristic_only", "Heuristic only");
		public static final BoolSetting SMART_HEURISTICS = new BoolSetting("smartHeuristics", "Smart heuristics",
			"No guessing on a server once it has said who sent a message (UUID and heuristic)", true);
		public static final BoolSetting SYSTEM_MESSAGES = new BoolSetting("handleSystemMessages", "Handle system messages",
			"Heads on the server's own messages too; many servers send players' chat that way", true);
		public static final BoolSetting HEAD_SHADOW = new BoolSetting("drawShadow", "Render shadow", "A shadow under each head, as text has", true);
		public static final IntSetting HEAD_PADDING = new IntSetting("rightPadding", "Chat heads right padding",
			"Empty pixels after a head; 0 touches the text, 1 matches text with its shadow", 1, 0, 16, 1, " px");
		public static final TextSetting NAME_ALIASES = new TextSetting("nameAliases", "Name aliases",
			"Nicknames matched to players, as nickname -> profilename, separated by commas", "", 400);
		public static final BoolSetting DETECT_ALIASES = new BoolSetting("detectNameAliases", "Auto-detect name aliases",
			"Learns a nickname from a /realname answer (\"Nick is Name\") on EssentialsX servers", true);
		public static final OptionSetting COPY = new OptionSetting("copyOnClick", "Copy on click", "Copies a message's text, while chat is open", 1,
			"off", "Off", "ctrlRight", "Ctrl + right click", "right", "Right click");
		public static final IntSetting MOVE_X = new IntSetting("moveRight", "Move right", null, 0, 0, 400, 1, "px");
		public static final IntSetting MOVE_Y = new IntSetting("moveUp", "Move up", null, 0, 0, 300, 1, "px");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Chat", "Tweak your chat display")
			.keywords("chat", "timestamps", "history", "stack", "tabs", "search", "calculator", "heads", "copy").icon(IconId.GAUGE)
			.category(Category.UTILITY)
			.section("General", NO_BRACKETS, SEARCH, SEARCH_KEY, TOGGLE_KEY, BACKGROUND)
			.section("Smooth Chat", SMOOTH, SMOOTH_TIME, SMOOTH_INPUT, INPUT_TIME, INPUT_OFFSET, INPUT_EASING)
			.section("Chat Tabs", TABS, TAB_OWN, TAB_KEY, TAB_NAME, TAB_WORDS)
			.section("Chat Calculator", CALC, CALC_PLACES, CALC_INPUT, CALC_OUTPUT, CALC_ERROR)
			.section("Chat Avatars", AVATARS, HEAD_POSITION, THREE_D, ALIGN, SENDER_DETECTION, SMART_HEURISTICS, SYSTEM_MESSAGES, HEAD_SHADOW,
				HEAD_PADDING, NAME_ALIASES, DETECT_ALIASES, ROUND_AVATARS)
			.section("Message Timestamps", TIMESTAMPS, TIME_FORMAT, TIME_COLOR).section("Stack Messages", STACK, CONSECUTIVE)
			.section("Chat Settings", HISTORY, KEEP, COPY).section("Mentions", MENTIONS, MENTION_COLOR)
			.section("Position", MOVE_X, MOVE_Y)
			.note("Changes only how chat shows on your screen: what you send, and Minecraft's chat signing, its secure-chat indicators and "
				+ "reporting stay as they are. Translating chat would need a translation service, so it isn't offered.").build();

		/** One of Minecraft's 16 chat colours, in their own order (black 0 … white 15). */
		private static OptionSetting chatColor(String id, String label, int defaultIndex) {
			return new OptionSetting(id, label, null, defaultIndex, "black", "Black", "darkBlue", "Dark blue", "darkGreen", "Dark green", "darkAqua",
				"Dark aqua", "darkRed", "Dark red", "darkPurple", "Dark purple", "gold", "Gold", "gray", "Gray", "darkGray", "Dark gray", "blue", "Blue",
				"green", "Green", "aqua", "Aqua", "red", "Red", "lightPurple", "Light purple", "yellow", "Yellow", "white", "White");
		}
	}

	public static final class TabEditor {
		public static final String ID = "tab_editor";
		public static final BoolSetting HIDE_HEADER = new BoolSetting("hideHeader", "Disable header", false);
		public static final BoolSetting HIDE_FOOTER = new BoolSetting("hideFooter", "Disable footer", false);
		public static final BoolSetting HIDE_HEADS = new BoolSetting("hideHeads", "Disable player heads", false);
		public static final BoolSetting PING_NUMBER = new BoolSetting("pingNumber", "Display ping as number",
			"Ping View's numbers in place of the signal bars, in a wider column", true);
		// Ping View's options.
		public static final RgbSetting PING_GOOD = new RgbSetting("pingColorGood", "Good ping color", "Under 50 ms", 0x1EFF00);
		public static final RgbSetting PING_OK = new RgbSetting("pingColorOk", "Ok ping color", "50 to 99 ms", 0xFFF100);
		public static final RgbSetting PING_BAD = new RgbSetting("pingColorBad", "Bad ping color", "100 to 199 ms", 0xFF9500);
		public static final RgbSetting PING_TERRIBLE = new RgbSetting("pingColorTerrible", "Terrible ping color", "200 ms and more", 0xFF3B3B);
		public static final RgbSetting PING_UNKNOWN = new RgbSetting("pingColorUnknown", "Unknown ping color", "Shown as N/A", 0x555555);
		public static final BoolSetting PING_MS = new BoolSetting("showMs", "Show 'ms' suffix", true);
		public static final BoolSetting PING_NAMETAGS = new BoolSetting("showNametagPing", "Show ping on nametags",
			"Each player's ping after their name, in the same colors", false);
		public static final BoolSetting PING_SHADOW = new BoolSetting("textShadow", "Text shadow", true);
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
			.section("General", HIDE_HEADER, HIDE_FOOTER, HIDE_HEADS, PING_NUMBER, HOIST, EXTEND, TOGGLE)
			.section("Ping View", PING_GOOD, PING_OK, PING_BAD, PING_TERRIBLE, PING_UNKNOWN, PING_MS, PING_NAMETAGS, PING_SHADOW)
			.section("Color", BACKGROUND, BACKGROUND_OPACITY, ROW, ROW_OPACITY)
			.note("Players, their names, teams and order are the server's; only how the list is drawn changes (and you first, when asked). "
				+ "Ping is what the server reports for each player.").build();
	}

	public static final class Scoreboard {
		public static final String ID = "scoreboard";
		public static final BoolSetting HIDE = new BoolSetting("hide", "Hide scoreboard", false);
		public static final BoolSetting NUMBERS = new BoolSetting("showNumbers", "Show numbers", "The red score numbers", false);
		public static final IntSetting OPACITY = new IntSetting("opacity", "Background opacity", null, 30, 0, 100, 5, "%");
		public static final RgbSetting BACKGROUND_COLOR = new RgbSetting("backgroundColor", "Background color", null, 0x000000);
		public static final RgbSetting TITLE_BACKGROUND = new RgbSetting("titleBackground", "Title background", null, 0x000000, true);
		public static final BoolSetting BORDER = new BoolSetting("border", "Border", false);
		public static final RgbSetting BORDER_COLOR = new RgbSetting("borderColor", "Border color", null, 0x000000);
		public static final RgbSetting TEXT_COLOR = new RgbSetting("textColor", "Text color", "Lines the server didn't color", 0xFFFFFF);
		public static final BoolSetting SHADOW = new BoolSetting("textShadow", "Text shadow", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Scoreboard", "Customize scoreboard")
			.keywords("scoreboard", "sidebar", "numbers", "server").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Scoreboard", HIDE, NUMBERS, SHADOW).section("Style", OPACITY, BACKGROUND_COLOR, TITLE_BACKGROUND, BORDER, BORDER_COLOR,
				TEXT_COLOR)
			.hud(HudPlacement.of(Anchor.RIGHT, 1, 0)).drawnByMinecraft()
			.note("The server's lines and their order are kept exactly; only how and where they're drawn changes. Move and resize it in the "
				+ "HUD editor like any other.").build();
	}

	/** Titles: Title Tweaks (its options, defaults and ranges), and printing titles to your own chat. */
	public static final class Titles {
		public static final String ID = "titles";
		public static final BoolSetting DISABLE = new BoolSetting("disableTitles", "Disable titles", "Remove titles entirely", false);
		public static final IntSetting SCALE = new IntSetting("titleScale", "Title scale", "Set the scale for titles", 100, 0, 100, 1, "%");
		public static final BoolSetting AUTO_SCALE = new BoolSetting("autoTitleScale", "Automatically scale titles",
			"Scale titles automatically if they go past the edges of your screen", true);
		public static final IntSetting TITLE_OFFSET = new IntSetting("titlePositionOffset", "Title vertical position",
			"Offset the vertical position of the title by the specified number; extreme values may push it off screen", 0, -100, 100, 1, "");
		public static final IntSetting SUBTITLE_OFFSET = new IntSetting("subtitlePositionOffset", "Subtitle vertical position",
			"Offset the vertical position of the subtitle by the specified number; extreme values may push it off screen", 0, -100, 100, 1, "");
		public static final IntSetting OPACITY = new IntSetting("titleOpacity", "Title opacity", "Set the opacity for titles", 100, 0, 100, 1, "%");
		public static final BoolSetting NO_SHADOW = new BoolSetting("removeTextShadow", "Remove text shadow", "Removes the text shadow from titles",
			false);
		public static final BoolSetting CLEAR_ON_DISCONNECT = new BoolSetting("clearOnDisconnect", "Clear on disconnect",
			"Fixes a Minecraft bug where titles fail to clear upon disconnecting from a server, leaving a title stuck on your screen", true);
		public static final BoolSetting PRINT = new BoolSetting("printTitles", "Print title & subtitle to chat", false);
		public static final BoolSetting PRINT_ACTIONBAR = new BoolSetting("printActionbar", "Print actionbar title to chat",
			"Repeats of the same text aren't printed again", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Titles", "Improvements and customization for titles")
			.keywords("title", "subtitle", "actionbar", "chat", "title tweaks").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Title Tweaks", DISABLE, SCALE, AUTO_SCALE, TITLE_OFFSET, SUBTITLE_OFFSET, OPACITY, NO_SHADOW, CLEAR_ON_DISCONNECT)
			.section("Print Titles to Chat", PRINT, PRINT_ACTIONBAR)
			.note("Printed lines go into your own chat only, marked as a title.").build();
	}

	public static final class BossBar {
		public static final String ID = "boss_bar";
		public static final BoolSetting HIDE = new BoolSetting("hide", "Hide boss bars", false);
		public static final BoolSetting SHADOW = new BoolSetting("textShadow", "Text shadow", true);
		public static final BoolSetting OVERWRITE = new BoolSetting("overwriteColor", "Overwrite text color", false);
		public static final RgbSetting COLOR = new RgbSetting("color", "Text color", null, 0xFFFFFF);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Boss Bar", "Customize your bossbar")
			.keywords("boss", "bar", "wither", "dragon").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Boss Bar", HIDE, SHADOW, OVERWRITE, COLOR)
			.hud(HudPlacement.of(Anchor.TOP, 0, 3)).drawnByMinecraft()
			.note("Every bar the server sends is drawn, with its own progress and color; several stack as Minecraft stacks them. Move and "
				+ "resize them in the HUD editor like any other.").build();
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
		// ClipboardScreenshot's options.
		public static final KeySetting COPY_KEY = new KeySetting("copyKey", "Copy screenshot to clipboard",
			"Copies what's on screen to the clipboard as an image (F2 also saves the usual screenshot)", "key.keyboard.f2").inScreens();
		public static final OptionSetting NOTIFICATION = new OptionSetting("notificationType", "Notification",
			"If set to Chat or Action Bar but unavailable, it falls back to a popup instead", 0, "chat", "Chat", "actionbar", "Action Bar", "toast",
			"Toast", "none", "None");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Screenshot", "Screenshot preview, death shots and save options")
			.keywords("screenshot", "f2", "preview", "clipboard", "jpeg").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Screenshot", AFTER, ON_DEATH, JPEG).section("Screenshot Preview", PREVIEW, DURATION, SIZE, ANIMATION, CORNER, X_OFFSET, Y_OFFSET,
				BORDER)
			.section("Screenshot to Clipboard", COPY_KEY, NOTIFICATION)
			.note("Screenshots stay on this computer, in Minecraft's screenshots folder and your clipboard: nothing is uploaded.")
			.build();
	}

	/**
	 * Waypoints, as wWaypoints marks them: a label over the place (its badge with the first letter, or its name, with the
	 * distance under it while you look at it), shrinking with distance on wWaypoints' curve and fading when you're on top of
	 * it, and the block itself outlined and lightly filled in the waypoint's colour. Its settings are wWaypoints' (General,
	 * Defaults, Label stylist, Block), with Flint's sets, beams and chat sharing after them.
	 */
	public static final class Waypoints {
		public static final String ID = "waypoints";
		// Keys (wWaypoints: N creates, Hide Waypoints cycles; its M menu key is the World Map's in Flint, so the list keeps grave)
		public static final KeySetting CREATE_KEY = new KeySetting("createKey", "Create waypoint",
			"Tap: a waypoint on the block you stand on, or removes the one there. Sneak: the block you look at (16 blocks)", "key.keyboard.n");
		/** The Create key asks for a name and colour first (a small window with Create and Cancel). */
		public static final BoolSetting ASK_NAME = new BoolSetting("askName", "Name new waypoints",
			"The Create key opens a small window for the name and color, with Create and Cancel. Off: made at once", true);
		public static final KeySetting MENU_KEY = new KeySetting("menuKey", "Open waypoint menu", "The menu's Waypoints tab",
			"key.keyboard.grave.accent");
		public static final KeySetting TOGGLE_KEY = new KeySetting("toggleKey", "Hide waypoints",
			"Cycles: show all, show only the near ones (Hide key distance), hide all", KeySetting.NONE);
		public static final KeySetting NEXT_SET_KEY = new KeySetting("nextSetKey", "Next set", "Makes the next set active in this dimension",
			KeySetting.NONE);
		// General
		public static final BoolSetting ON_DEATH = new BoolSetting("onDeath", "Death waypoints", "A temporary waypoint where you die", false);
		public static final IntSetting DEATH_LIMIT = new IntSetting("deathLimit", "Death waypoint limit", "The newest are kept; 0 keeps every one",
			8, 0, 32, 1, "");
		public static final BoolSetting DEATH_REACHED = new BoolSetting("deathReached", "Remove when reached",
			"A death waypoint goes once you get back to it", false);
		public static final IntSetting DEATH_RANGE = new IntSetting("deathRange", "Reached within", null, 4, 1, 32, 1, " blocks");
		public static final BoolSetting BLOCK_MODEL = new BoolSetting("blockModel", "Use block model",
			"The highlight fits slabs, stairs, buttons and other shapes", true);
		public static final BoolSetting ZOOM_WITH_FOV = new BoolSetting("zoomWithFov", "Zoom with FOV",
			"Labels grow while a zoom narrows your view", false);
		public static final OptionSetting SORT = new OptionSetting("sortOrder", "Sort order", "The Waypoints list", 0, "proximity", "Proximity",
			"alphabetical", "Alphabetical", "custom", "Custom");
		public static final BoolSetting RANDOM_COLORS = new BoolSetting("randomColors", "Random colors",
			"New waypoints take the next color of the palette; off: orange", true);
		// Defaults (what waypoints show)
		public static final OptionSetting DISPLAY = new OptionSetting("display", "Display mode", "How waypoints show in the world", 0, "both",
			"Block + Label", "label", "Label only", "block", "Block only");
		public static final IntSetting MAX_DISTANCE = new IntSetting("maxDistance", "Max render distance", "In blocks; 0 shows labels at any "
			+ "distance (blocks show within your render distance)", 0, 0, 16384, 64, "");
		public static final IntSetting HIDE_WITHIN = new IntSetting("hideWithin", "Hide key distance",
			"The Hide key's middle step shows only waypoints this near; 0 leaves it out", 100, 0, 5000, 10, " blocks");
		// Label (wWaypoints' label stylist)
		public static final OptionSetting LABEL_STYLE = new OptionSetting("labelStyle", "Label preset", null, 2, "icon", "Icon", "text", "Text",
			"icon_colored", "Icon (Colored)", "text_colored", "Text (Colored)", "simple", "Simple");
		public static final FloatSetting LABEL_SCALE = new FloatSetting("nameScale", "Label size", null, 1, 0.5F, 3, 0.05F, "×");
		public static final FloatSetting LABEL_HEIGHT = new FloatSetting("labelHeight", "Label height", "How far above the block the label floats",
			2.5F, 1, 3, 0.1F, " blocks");
		public static final IntSetting LABEL_OPACITY = new IntSetting("labelOpacity", "Label background", null, 50, 0, 100, 5, "%");
		public static final FloatSetting FADE_START = new FloatSetting("fadeStart", "Fade start", "Labels fade out from this close",
			2, 0, 32, 0.5F, " blocks");
		public static final FloatSetting FADE_END = new FloatSetting("fadeEnd", "Fade end", "And are gone this close", 1, 0, 32, 0.5F,
			" blocks");
		public static final BoolSetting SHOW_DISTANCE = new BoolSetting("showDistance", "Show distance", true);
		public static final BoolSetting DISTANCE_FACING = new BoolSetting("distanceFacing", "Distance when facing",
			"The distance shows while you look at the waypoint", true);
		public static final BoolSetting ALWAYS_DISTANCE = new BoolSetting("alwaysDistance", "Always show distance",
			"Off: the distance fades out closer than 20 blocks", true);
		public static final BoolSetting TEXT_SHADOW = new BoolSetting("textShadow", "Text shadow", true);
		public static final IntSetting SHADOW_OPACITY = new IntSetting("shadowOpacity", "Shadow opacity", null, 70, 0, 100, 5, "%");
		// Block
		public static final BoolSetting THROUGH_WALLS = new BoolSetting("throughWalls", "Block through walls",
			"The highlight shows through other blocks", false);
		public static final IntSetting OUTLINE_OPACITY = new IntSetting("outlineOpacity", "Outline opacity", null, 100, 0, 100, 5, "%");
		public static final IntSetting FILL_OPACITY = new IntSetting("fillOpacity", "Fill opacity", null, 10, 0, 100, 5, "%");
		public static final FloatSetting OUTLINE_THICKNESS = new FloatSetting("outlineThickness", "Outline thickness", null, 2, 0, 10, 0.5F, "");
		// Beam (Flint's)
		public static final BoolSetting SHOW_BEAM = new BoolSetting("showBeam", "Show beams", "A beam of light from the ground to the sky, "
			+ "for waypoints whose beam is on", false);
		public static final FloatSetting BEAM_SCALE = new FloatSetting("beamScale", "Beam scale", "How wide the beam is", 1, 0.25F, 4, 0.05F, "×");
		// Sets and sharing (Flint's)
		public static final BoolSetting ALL_SETS = new BoolSetting("allSets", "Show all sets",
			"Off: each dimension shows only its active set (the world map chooses it)", true);
		public static final BoolSetting CHAT_LINKS = new BoolSetting("chatLinks", "Waypoints in chat",
			"Shared waypoints and coordinates in chat get a click to add them", true);
		public static final BoolSetting CHAT_NUMBERS = new BoolSetting("chatNumbers", "Bare numbers too", "Three numbers like 120, 64, -30",
			false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Waypoints", "Mark places and find your way back")
			.keywords("waypoint", "marker", "home", "death", "wwaypoints").icon(IconId.PIN).category(Category.WORLD)
			.section("Key Settings", CREATE_KEY, ASK_NAME, MENU_KEY, TOGGLE_KEY, NEXT_SET_KEY)
			.section("General", ON_DEATH, DEATH_LIMIT, DEATH_REACHED, DEATH_RANGE, BLOCK_MODEL, ZOOM_WITH_FOV, SORT, RANDOM_COLORS)
			.section("Defaults", DISPLAY, MAX_DISTANCE, HIDE_WITHIN)
			.section("Label", LABEL_STYLE, LABEL_SCALE, LABEL_HEIGHT, LABEL_OPACITY, FADE_START, FADE_END, SHOW_DISTANCE, DISTANCE_FACING,
				ALWAYS_DISTANCE, TEXT_SHADOW, SHADOW_OPACITY)
			.section("Block", THROUGH_WALLS, OUTLINE_OPACITY, FILL_OPACITY, OUTLINE_THICKNESS)
			.section("Beam", SHOW_BEAM, BEAM_SCALE)
			.section("Sets and Sharing", ALL_SETS, CHAT_LINKS, CHAT_NUMBERS)
			.needs(Requirement.WORLD)
			.note("Waypoints are kept per world or server and per dimension, on this computer only, and never sent anywhere unless you "
				+ "share one in chat. Tap the Create key to mark the block you stand on (tap again to remove it; sneak to mark the block "
				+ "you look at), or use Add here in the menu's Waypoints tab. Each waypoint shows its label, its block and optionally a "
				+ "beam; the minimap, the world map and the world show the same badge. A Nether waypoint shows only in the Nether: its "
				+ "coordinates are never converted.").build();
	}

	public static final class ShulkerPreview {
		public static final String ID = "shulker_preview";
		public static final BoolSetting HOLD = new BoolSetting("holdKey", "Hold a key to preview", "Off: the preview always shows", false);
		public static final KeySetting KEY = new KeySetting("previewKey", "Preview key", "Held over a container to see inside",
			"key.keyboard.left.shift").inScreens();
		public static final OptionSetting MODE = new OptionSetting("mode", "Preview", "Compact puts the same items together, most first; hold Alt "
			+ "for the other one", 0, "full", "Full", "compact", "Compact");
		public static final FloatSetting SCALE = new FloatSetting("containerScale", "Preview scale", null, 1, 0.5F, 2, 0.05F, "×");
		public static final BoolSetting COLORED = new BoolSetting("colored", "Box color", "The preview in the box's own color", true);
		public static final BoolSetting ENDER = new BoolSetting("enderChest", "Ender chest", "Your ender chest's items, as you last saw them inside it",
			true);
		public static final BoolSetting OTHERS = new BoolSetting("otherContainers", "Other containers",
			"Chests, barrels and anything else carrying items", true);
		public static final BoolSetting HINT = new BoolSetting("keyHint", "Key hint", "\u201CHold Shift to preview\u201D under a box until you do",
			true);
		public static final BoolSetting HIDE_LIST = new BoolSetting("hideList", "Hide the text list", "Minecraft's \u201C3x Stone\u201D lines under it",
			true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Shulker Preview", "See the contents of shulker boxes")
			.keywords("shulker", "container", "tooltip", "preview", "ender chest", "compact").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Container Tooltips", HOLD, KEY, HINT, MODE, SCALE, COLORED, HIDE_LIST).section("More Containers", ENDER, OTHERS)
			.note("A panel of the box's items in its tooltip, in the box's color, from the contents the game already sent with the item. "
				+ "The ender chest's items are only sent while it is open, so its preview is what you saw there last (this session, per "
				+ "world or server).").build();
	}

	public static final class ScrollableTooltips {
		public static final String ID = "scrollable_tooltips";
		public static final BoolSetting SNAP_START = new BoolSetting("snapToStart", "Snap to start",
			"Each new tooltip opens at its first line; off: it keeps where you scrolled", true);
		public static final KeySetting VERTICAL_KEY = new KeySetting("verticalKey", "Vertical scrolling key",
			"None: the mouse wheel alone scrolls a tooltip that doesn't fit", KeySetting.NONE).inScreens();
		public static final KeySetting HORIZONTAL_KEY = new KeySetting("horizontalKey", "Horizontal scrolling key",
			"Hold it while turning the wheel to move a wide tooltip sideways", "key.keyboard.left.shift").inScreens();
		public static final KeySetting SCALE_KEY = new KeySetting("scaleKey", "Scale key",
			"Hold it while turning the wheel to make the tooltip smaller or larger", "key.keyboard.left.control").inScreens();
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
		/**
		 * The search bar above chests and your inventory, off unless chosen. Its key was {@code search} (on by default) before; that
		 * key is retired rather than read, so the bar starts off for everyone and shows only once turned on here.
		 */
		public static final BoolSetting SEARCH = new BoolSetting("searchBar", "Inventory search bar",
			"A search bar above chests and your inventory. Off: nothing is drawn and no keys are taken", false);
		public static final OptionSetting HIGHLIGHT = new OptionSetting("highlight", "Highlight", "How matching items stand out", 0, "dim",
			"Dim others", "outline", "Outline matches");
		public static final BoolSetting SEARCH_LORE = new BoolSetting("searchLore", "Always search lore", "Also matches an item's lore lines",
			false);
		public static final KeySetting SEARCH_KEY = new KeySetting("searchKey", "Open with keybind", "Puts the cursor in the search box",
			KeySetting.NONE).inScreens();
		// Inventory HUD
		public static final BoolSetting HUD = new BoolSetting("hud", "Inventory HUD", "Your 27 inventory slots on screen (move it in the HUD "
			+ "editor)", false);
		public static final BoolSetting HUD_EMPTY = new BoolSetting("hudShowEmpty", "Show empty", "Also when your inventory is empty", false);
		public static final BoolSetting HUD_BACKGROUND = new BoolSetting("hudBackground", "Show background", true);
		// Slot locking
		public static final BoolSetting LOCKING = new BoolSetting("slotLocking", "Slot locking", "Locked slots can't be clicked, moved or dropped",
			true);
		public static final KeySetting LOCK_KEY = new KeySetting("lockKey", "Lock key", "Hold the pointer over a slot of your inventory and press "
			+ "it to lock or unlock", "key.keyboard.l").inScreens();
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
		// Slot binding
		public static final BoolSetting BINDING = new BoolSetting("slotBinding", "Slot binding",
			"Shift-click a bound slot in your inventory to swap it with its hotbar slot", true);
		public static final KeySetting BIND_KEY = new KeySetting("bindKey", "Bind key", "Press it over an inventory slot, then over a hotbar "
			+ "slot; press it over a bound slot to unbind it", "key.keyboard.j").inScreens();
		public static final RgbSetting BIND_COLOR = new RgbSetting("bindColor", "Link color", null, 0x6CC6FF);
		public static final IdListSetting BOUND = new IdListSetting("boundSlots", "Bound slots", null, IdKind.SLOT_BINDING, java.util.List.of());
		// Enchantment summaries
		public static final BoolSetting ENCHANTS = new BoolSetting("enchantSummaries", "Enchantment summaries",
			"Each enchantment's first letters and level on the item's slot", false);
		public static final IntSetting ENCHANTS_MAX = new IntSetting("enchantSummariesMax", "Most shown", "Per slot (two beside a durability number)", 3, 1, 3, 1, "");
		public static final BoolSetting ENCHANTS_BOOKS = new BoolSetting("enchantSummariesBooks", "On enchanted books", true);
		public static final RgbSetting ENCHANTS_COLOR = new RgbSetting("enchantSummariesColor", "Text color", null, 0xD8B4FE);
		public static final RgbSetting ENCHANTS_TOP_COLOR = new RgbSetting("enchantSummariesMaxColor", "Max level color",
			"An enchantment at its highest level", 0xFFC24B);
		// Open / close animations
		public static final BoolSetting ANIMATIONS = new BoolSetting("inventoryOpenCloseAnimations", "Open/close animations",
			"Chests and your inventory slide in as they open and out as they close", false);
		public static final IntSetting ANIMATION_MS = new IntSetting("openCloseDuration", "Animation duration", null, 300, 50, 1000, 50, " ms");
		public static final OptionSetting EASING = new OptionSetting("openCloseEasing", "Easing", null, 0, "out_quint", "Out quint", "out_cubic",
			"Out cubic", "out_back", "Out back", "linear", "Linear");
		public static final BoolSetting MOVE = new BoolSetting("moveScreenInOut", "Move screen in/out", true);
		public static final OptionSetting DIRECTION = new OptionSetting("moveScreenDirection", "Move screen direction", null, 0, "down",
			"From below", "up", "From above", "left", "From the left", "right", "From the right");
		public static final BoolSetting SCALE = new BoolSetting("scaleScreenInOut", "Scale screen in/out", false);
		// Rarity border and durability text
		public static final BoolSetting RARITY = new BoolSetting("rarityBorderEnabled", "Rarity border",
			"Uncommon, rare and epic items get a frame in their rarity's color", false);
		public static final IntSetting RARITY_THICKNESS = new IntSetting("rarityBorderThickness", "Border thickness", null, 1, 1, 3, 1, " px");
		public static final BoolSetting DURABILITY = new BoolSetting("durabilityText", "Durability text", "A worn item's uses left on its slot",
			false);
		public static final IntSetting DURABILITY_SCALE = new IntSetting("durabilityTextScale", "Text scale", "It shrinks further to fit the slot",
			50, 25, 100, 5, "%");
		// Hotbar customizer
		public static final BoolSetting RARITY_HOTBAR = new BoolSetting("rarityBorderHotbar", "Rarity border in hotbar", true);
		public static final BoolSetting DURABILITY_HOTBAR = new BoolSetting("durabilityTextHotbar", "Durability text in hotbar", false);
		public static final BoolSetting ENCHANTS_HOTBAR = new BoolSetting("enchantSummariesHotbar", "Enchant summaries in hotbar", false);
		public static final BoolSetting SELECTED_FRAME = new BoolSetting("hotbarSelectedFrame", "Selected slot frame",
			"A colored frame on the hotbar slot you hold", false);
		public static final RgbSetting SELECTED_COLOR = new RgbSetting("hotbarSelectedColor", "Frame color", null, 0xFF8139);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Inventory", "Inventory screen tweaks")
			.keywords("inventory", "items", "search", "lock", "slots", "drop", "bind", "enchantments", "animation", "rarity", "durability",
				"hotbar").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Inventory Search", SEARCH, HIGHLIGHT, SEARCH_LORE, SEARCH_KEY).section("Inventory HUD", HUD, HUD_EMPTY, HUD_BACKGROUND)
			.section("Slot Locking", LOCKING, LOCK_KEY, LOCK_COLOR, LOCKED).section("Slot Binding", BINDING, BIND_KEY, BIND_COLOR, BOUND)
			.section("Item Drop Protection", PROTECT, PROTECTED, PROTECT_CONTAINERS)
			.section("Enchantment Summaries", ENCHANTS, ENCHANTS_MAX, ENCHANTS_BOOKS, ENCHANTS_COLOR, ENCHANTS_TOP_COLOR)
			.section("Open / Close Animations", ANIMATIONS, ANIMATION_MS, EASING, MOVE, DIRECTION, SCALE)
			.section("Rarity Border", RARITY, RARITY_THICKNESS).section("Durability Text", DURABILITY, DURABILITY_SCALE)
			.section("Hotbar Customizer", RARITY_HOTBAR, DURABILITY_HOTBAR, ENCHANTS_HOTBAR, SELECTED_FRAME, SELECTED_COLOR)
			.section("Hotbar Key Overlay", KEYS, KEYS_POSITION, KEYS_COLOR, KEYS_SMALL, KEYS_LOWERCASE, KEYS_SHADOW, KEYS_HIDE_EMPTY,
				KEYS_HIDE_SELECTED)
			.section("Don't Reset Cursor", KEEP_CURSOR, CURSOR_MS, CURSOR_CONTAINERS)
			.hud(com.flintclient.client.control.hud.HudPlacement.of(com.flintclient.client.control.hud.Anchor.BOTTOM_RIGHT, 4, 130))
			.needs(Requirement.WORLD)
			.note("Everything here acts on your side only. Nothing moves items for you except a Shift-click on a bound slot, which makes the "
				+ "one swap its hotbar number key would. Locks and drop protection stop your own clicks and keys; a server, a plugin or dying "
				+ "can still move or drop items. Not in the creative inventory.").build();
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

	static ModuleDescriptor notBuilt(String id, String name, String description, String why, String... keywords) {
		return ModuleDescriptor.builder(id, name, description).keywords(keywords).category(Category.UTILITY).notBuilt(why).build();
	}

	public static final class DeathInfo {
		public static final String ID = "death_info";
		public static final BoolSetting CHAT = new BoolSetting("chat", "In chat", "A line only you see, the moment you die", false);
		public static final BoolSetting SCREEN = new BoolSetting("screen", "On the death screen", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Death Info", "See where you died")
			.keywords("death", "coordinates", "died", "respawn").icon(IconId.PIN).category(Category.UTILITY).section("Death Coordinates", CHAT, SCREEN)
			.needs(Requirement.WORLD).build();
	}

	public static final class Reconnect {
		public static final String ID = "reconnect";
		public static final BoolSetting AUTO = new BoolSetting("auto", "Auto reconnect", "Off: a Reconnect button only", false);
		public static final IntSetting ATTEMPTS = new IntSetting("attempts", "Attempts", "Tries in a row before it stops", 3, 1, 100, 1, "");
		public static final FloatSetting DELAY = new FloatSetting("delay", "Delay", "Before the first try", 5, 1, 300, 0.5F, " s");
		public static final BoolSetting INCREMENT_DELAY = new BoolSetting("incrementDelay", "Increment delay", "Wait longer after each failed try",
			false);
		public static final FloatSetting INCREMENT = new FloatSetting("increment", "Increment", null, 5, 1, 20, 0.5F, " s");
		public static final FloatSetting MAX_DELAY = new FloatSetting("maxDelay", "Max delay", null, 20, 3, 300, 0.5F, " s");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Reconnect", "Rejoin a server after you get disconnected")
			.keywords("reconnect", "rejoin", "disconnect", "kicked").icon(IconId.SIGNAL).category(Category.UTILITY)
			.section("General", AUTO, ATTEMPTS, DELAY, INCREMENT_DELAY, INCREMENT, MAX_DELAY)
			.note("Adds a Reconnect button to the disconnected screen; with Auto reconnect it presses it for you, a few times at most. Never "
				+ "for a singleplayer world, and Cancel always stops it.").build();
	}

	/** Toast Control (Shadows_of_Fire's ToastControl): which toasts are blocked, how many show at once, where and how they move. */
	public static final class ToastControl {
		public static final String ID = "toast_control";
		// ---------- Blocked Toasts ----------
		public static final BoolSetting BLOCK_ADVANCEMENTS = new BoolSetting("blockAdvancements", "Block advancements",
			"If advancement toasts are blocked", true);
		public static final BoolSetting BLOCK_RECIPES = new BoolSetting("blockRecipes", "Block recipes", "If recipe toasts are blocked", true);
		public static final BoolSetting BLOCK_SYSTEM = new BoolSetting("blockSystem", "Block system", "If system toasts are blocked", false);
		public static final BoolSetting BLOCK_TUTORIAL = new BoolSetting("blockTutorial", "Block tutorial", "If tutorial toasts are blocked", true);
		public static final BoolSetting BLOCK_VANILLA = new BoolSetting("blockAllVanilla", "Block all vanilla", "If all vanilla toasts are blocked",
			false);
		public static final BoolSetting BLOCK_MODDED = new BoolSetting("blockAllModded", "Block all modded",
			"If all non-vanilla toasts are blocked (Flint's own notices are not vanilla)", false);
		public static final BoolSetting BLOCK_ALL = new BoolSetting("blockAll", "Block all", "If all toasts are blocked", false);
		public static final TextSetting BLOCKED_CLASSES = new TextSetting("blockedClasses", "Blocked classes",
			"Toast classes that are blocked from being shown, comma separated (Print classes logs a toast's class)", "", 1000);
		// ---------- Visual Options ----------
		public static final IntSetting FORCE_TIME = new IntSetting("forceTime", "Force time",
			"How long a toast must stay on the screen, in ticks; 0 uses the default time", 0, 0, 4000, 1, "");
		public static final IntSetting TOAST_COUNT = new IntSetting("toastCount", "Toast count", "How many toasts will be displayed on screen at once",
			3, 1, 7, 1, "");
		public static final IntSetting X_OFFSET = new IntSetting("xOffset", "X offset", "The X offset for toasts to be drawn at", 0, -1000, 1000, 1, "");
		public static final IntSetting Y_OFFSET = new IntSetting("yOffset", "Y offset", "The Y offset for toasts to be drawn at", 0, -1000, 1000, 1, "");
		public static final BoolSetting NO_SLIDE = new BoolSetting("noSlide", "No slide animation",
			"If toasts automatically pop into the screen without animations", false);
		public static final BoolSetting START_LEFT = new BoolSetting("anchorLeft", "Start left", "If toasts show on the left of the screen", false);
		public static final BoolSetting TOP_DOWN = new BoolSetting("topDown", "Top down",
			"If toasts will come in from the top of the screen, rather than the side", false);
		public static final BoolSetting SOUND = new BoolSetting("sound", "Play sound", "A toast's own sound (a challenge's fanfare) as it appears",
			true);
		public static final FloatSetting SCALE = new FloatSetting("scale", "Scale", null, 1, 0.05F, 2, 0.05F, "×");
		public static final KeySetting CLEAR_KEY = new KeySetting("clearKey", "Clear toasts", "Removes every toast on screen and waiting",
			"key.keyboard.j");
		// ---------- Debug ----------
		public static final BoolSetting PRINT_CLASSES = new BoolSetting("printClasses", "Print classes",
			"If toast classes are printed to the log when they are shown", false);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Toast Control", "Blocking annoying popups")
			.keywords("toast", "popup", "advancement", "recipe", "notification").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Blocked Toasts", BLOCK_ADVANCEMENTS, BLOCK_RECIPES, BLOCK_SYSTEM, BLOCK_TUTORIAL, BLOCK_VANILLA, BLOCK_MODDED, BLOCK_ALL,
				BLOCKED_CLASSES)
			.section("Visual Options", FORCE_TIME, TOAST_COUNT, X_OFFSET, Y_OFFSET, NO_SLIDE, START_LEFT, TOP_DOWN, SOUND, SCALE, CLEAR_KEY)
			.section("Debug", PRINT_CLASSES)
			.note("Minecraft's music \"Now playing\" toast is its own setting and is left alone.").build();
	}
}
