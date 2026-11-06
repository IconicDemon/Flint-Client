package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.Category;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IconId;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.IdValuesSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.KeySetting;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleDescriptor.Requirement;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.RgbSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.TextSetting;
import java.util.ArrayList;
import java.util.List;

/**
 * Flint's utility and world mods added with the menu's full set: key macros, log and world backups clean-up, dropped
 * item flashing and sizes, elytra and totem extras, coloured subtitles, Hypixel helpers and Discord details. Their ids
 * follow the reference menu's where the meaning is the same, so its rows name them. Version-neutral.
 */
public final class MoreMods {
	private MoreMods() {
	}

	/** Hours, days, weeks or months: how a "delay" setting counts. */
	static OptionSetting delayUnit(String id, String label, int defaultIndex) {
		return new OptionSetting(id, label, null, defaultIndex, "hours", "Hours", "days", "Days", "weeks", "Weeks", "months", "Months");
	}

	/** {@code amount} of {@code unit} (an index of {@link #delayUnit}) in milliseconds; months are 30 days. */
	public static long delayMillis(int unit, int amount) {
		long hour = 3_600_000L;
		long per = switch (unit) {
			case 0 -> hour;
			case 1 -> 24 * hour;
			case 2 -> 7 * 24 * hour;
			default -> 30 * 24 * hour;
		};
		return per * Math.max(0, amount);
	}

	public static final class AutoText {
		public static final String ID = "auto_text";
		public static final int SLOTS = 8;
		public static final KeySetting[] KEYS = new KeySetting[SLOTS];
		public static final TextSetting[] TEXTS = new TextSetting[SLOTS];
		public static final ModuleDescriptor DESCRIPTOR;

		static {
			ModuleDescriptor.Builder b = ModuleDescriptor.builder(ID, "Auto Text", "Send a message or command with one key")
				.keywords("macro", "auto text", "keybind", "command", "chat", "gg").icon(IconId.GAUGE).category(Category.UTILITY)
				.needs(Requirement.WORLD)
				.note("Press a macro's key in game (not while typing) to send its text; text starting with / runs as a command. "
					+ "A macro sends at most once a second.");
			for (int i = 0; i < SLOTS; i++) {
				KEYS[i] = new KeySetting("key" + (i + 1), "Key", null, KeySetting.NONE);
				TEXTS[i] = new TextSetting("text" + (i + 1), "Text", "A chat message, or a command starting with /", "", 256);
				b.section("Macro " + (i + 1), KEYS[i], TEXTS[i]);
			}
			DESCRIPTOR = b.build();
		}
	}

	public static final class CullLogs {
		public static final String ID = "cull_logs";
		public static final IntSetting MIN_LOGS = new IntSetting("minLogsBeforeCull", "Min logs before cull", "The newest ones always stay", 3, 0, 100,
			1, "");
		public static final OptionSetting UNIT = delayUnit("cullDelayType", "Cull delay type", 1);
		public static final IntSetting AMOUNT = new IntSetting("cullDelayAmount", "Cull delay amount", "Logs older than this are removed", 3, 0, 365, 1,
			"");
		public static final BoolSetting NOTIFY = new BoolSetting("showNotification", "Show notification", "Says how many it removed", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Cull Logs", "Remove old log files on their own")
			.keywords("logs", "cull", "clean", "disk", "files").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", MIN_LOGS, UNIT, AMOUNT, NOTIFY)
			.note("At start-up (and when turned on), the logs folder's old .log and .log.gz files are removed; the one in use never.")
			.build();
	}

	public static final class Backups {
		public static final String ID = "backups";
		public static final KeySetting SAVE_KEY = new KeySetting("keySaveBackup", "Save backup", "Backs up the world you're in now", KeySetting.NONE);
		public static final BoolSetting ON_LEAVE = new BoolSetting("saveBackupOnLeave", "Save backup on leave", "When you leave a singleplayer world",
			false);
		public static final BoolSetting SCHEDULED = new BoolSetting("scheduledBackups", "Scheduled backups", "While you play, every so often", false);
		public static final OptionSetting SCHEDULE_UNIT = delayUnit("scheduleDelayType", "Delay type", 1);
		public static final IntSetting SCHEDULE_AMOUNT = new IntSetting("scheduleDelayAmount", "Schedule delay amount", null, 1, 1, 365, 1, "");
		public static final BoolSetting CULL = new BoolSetting("cullBackups1", "Auto cull backups", "Removes this world's old backups", true);
		public static final IntSetting MIN_BACKUPS = new IntSetting("minBackupsBeforeCull", "Minimum backups before culling", null, 3, 0, 100, 1, "");
		public static final OptionSetting CULL_UNIT = delayUnit("cullDelayType", "Cull delay type", 1);
		public static final IntSetting CULL_AMOUNT = new IntSetting("cullDelayAmount", "Cull delay amount", null, 3, 0, 365, 1, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Backups", "Back up your singleplayer worlds")
			.keywords("backup", "save", "world", "zip", "restore").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", SAVE_KEY, ON_LEAVE).section("Scheduled Backups", SCHEDULED, SCHEDULE_UNIT, SCHEDULE_AMOUNT)
			.section("Auto Cull Backups", CULL, MIN_BACKUPS, CULL_UNIT, CULL_AMOUNT)
			.note("A backup is a .zip of the world in Minecraft's backups folder, the same as Edit > Make Backup, made after the world "
				+ "is saved. Singleplayer worlds only.")
			.build();
	}

	public static final class ItemDespawn {
		public static final String ID = "item_despawn";
		public static final IntSetting LIFETIME = new IntSetting("itemLifetimeSeconds", "Item lifetime", "How long the server keeps a dropped item",
			300, 5, 1800, 5, " s");
		public static final IntSetting FLASH_FROM = new IntSetting("flashLifetimeMaxSeconds", "Flash from", "Starts flashing this long before it goes",
			20, 1, 300, 1, " s");
		public static final IntSetting RATE = new IntSetting("flashRateTicks", "Flash rate", null, 10, 1, 20, 1, " ticks");
		public static final BoolSetting DYNAMIC = new BoolSetting("dynamicFlashRate", "Dynamic flash rate", "Flashes faster as it gets closer", true);
		public static final IntSetting MIN_RATE = new IntSetting("minDynamicFlashTicks", "Fastest flash", null, 5, 1, 20, 1, " ticks");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Item Despawn", "Dropped items flash before they despawn")
			.keywords("items", "despawn", "flash", "drop", "lifetime").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", LIFETIME, FLASH_FROM, RATE, DYNAMIC, MIN_RATE).needs(Requirement.WORLD)
			.note("Counted from when your game first saw the item, so one dropped before you arrived may last longer than shown.")
			.build();
	}

	public static final class UhcOverlay {
		public static final String ID = "uhc_overlay";
		public static final FloatSetting GOLDEN_APPLE = scale("goldenAppleScale", "Golden apple scale");
		public static final FloatSetting GOD_APPLE = scale("godAppleScale", "God apple scale");
		public static final FloatSetting GOLD_ORE = scale("goldOreScale", "Gold ore scale");
		public static final FloatSetting GOLD_INGOT = scale("goldIngotScale", "Gold ingot scale");
		public static final FloatSetting GOLD_NUGGET = scale("goldenNuggetScale", "Gold nugget scale");
		public static final FloatSetting SKULL = scale("skullScale", "Skull scale");
		public static final FloatSetting ARMOR = scale("armorScale", "Armor scale");
		public static final FloatSetting XP_BOTTLE = scale("xpBottleScale", "XP bottle scale");
		public static final FloatSetting PEARL = scale("enderpearlScale", "Ender pearl scale");
		public static final FloatSetting COBWEB = scale("cobwebScale", "Cobweb scale");
		public static final FloatSetting TOTEM = scale("totemScale", "Totem of Undying scale");
		public static final FloatSetting MACE = scale("maceScale", "Mace scale");
		/**
		 * Any item of this Minecraft version the player searches for and adds, each with its own size (hundredths: 150 is
		 * 1.50×), shown as a row of its own under the sizes above. An added item's size wins over those.
		 */
		public static final IdValuesSetting ITEMS = new IdValuesSetting("customItems", "Add item",
			"Search any item, add it and choose how big it shows on the ground", IdKind.ITEM, 100, 100, 400, 5, "×").addable(150).times();
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "UHC Overlay", "Make popular UHC items bigger on the ground")
			.keywords("uhc", "items", "gold", "apple", "bigger", "drops", "search", "add").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("Scale", GOLDEN_APPLE, GOD_APPLE, GOLD_ORE, GOLD_INGOT, GOLD_NUGGET, SKULL, ARMOR, XP_BOTTLE, PEARL, COBWEB, TOTEM, MACE, ITEMS)
			.needs(Requirement.WORLD).build();

		private static FloatSetting scale(String id, String label) {
			return new FloatSetting(id, label, null, 1, 1, 4, 0.05F, "×");
		}
	}

	public static final class Elytras {
		public static final String ID = "elytras";
		public static final BoolSetting TOOLTIP = new BoolSetting("estimateTimeTooltip", "Estimate time tooltip",
			"An elytra's tooltip says how much flight it has left", true);
		public static final BoolSetting PHYSICS = new BoolSetting("elytraPhysics", "Elytra physics", "Wings sway as you move, like a cape", true);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Elytras", "Elytra flight time and swaying wings")
			.keywords("elytra", "wings", "flight", "durability").icon(IconId.GAUGE).category(Category.VISUAL)
			.section("General", TOOLTIP).section("Appearance", PHYSICS).build();
	}

	public static final class Totem {
		public static final String ID = "totem";
		public static final BoolSetting COUNTER = new BoolSetting("counterEnabled", "Pop counter", "How many totems each player has used, by their name",
			false);
		public static final KeySetting RESET_KEY = new KeySetting("resetKeybind", "Reset counter", null, KeySetting.NONE);
		public static final FloatSetting PARTICLE_SCALE = new FloatSetting("scale", "Particle scale", null, 1, 0.1F, 2, 0.05F, "×");
		public static final FloatSetting PARTICLE_LIFE = new FloatSetting("maxAge", "Particle lifetime", "Against Minecraft's", 1, 0.1F, 2, 0.05F, "×");
		public static final BoolSetting CUSTOM_COLOR = new BoolSetting("customColor", "Custom colors", false);
		public static final RgbSetting COLOR_1 = new RgbSetting("color1", "Color 1", null, 0x999900);
		public static final RgbSetting COLOR_2 = new RgbSetting("color2", "Color 2", null, 0x196600);
		public static final BoolSetting SHOW_OWN = new BoolSetting("showOwn", "Show own", "Particles when you use one", true);
		public static final FloatSetting HAND_SCALE = new FloatSetting("totemScale", "In hand scale", "A totem held in first person", 1, 0, 1.5F, 0.05F,
			"×");
		public static final BoolSetting POP_ANIMATION = new BoolSetting("showPopAnimation", "Show pop animation", "The big totem on screen", true);
		public static final FloatSetting POP_SCALE = new FloatSetting("popScale", "Pop scale", null, 1, 0.3F, 1.5F, 0.05F, "×");
		public static final FloatSetting POP_X = new FloatSetting("popXOffset", "Pop X offset", null, 0, -1, 1, 0.05F, "");
		public static final FloatSetting POP_Y = new FloatSetting("popYOffset", "Pop Y offset", null, 0, -1, 1, 0.05F, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Totem", "Totem pop counter, particles and animation")
			.keywords("totem", "pop", "undying", "counter", "pvp").icon(IconId.GAUGE).category(Category.COMBAT)
			.section("Nametag Counter", COUNTER, RESET_KEY)
			.section("Particle Settings", PARTICLE_SCALE, PARTICLE_LIFE, CUSTOM_COLOR, COLOR_1, COLOR_2, SHOW_OWN)
			.section("General Settings", HAND_SCALE, POP_ANIMATION, POP_SCALE, POP_X, POP_Y).needs(Requirement.WORLD).build();
	}

	public static final class Subtitles {
		public static final String ID = "subtitles";
		/** Minecraft's sixteen chat colours, and None (Minecraft's white). */
		static OptionSetting color(String id, String label, int defaultIndex) {
			return new OptionSetting(id, label, null, defaultIndex, "none", "None", "black", "Black", "dark_blue", "Dark Blue", "dark_green", "Dark Green",
				"dark_aqua", "Dark Aqua", "dark_red", "Dark Red", "dark_purple", "Dark Purple", "gold", "Gold", "gray", "Gray", "dark_gray", "Dark Gray",
				"blue", "Blue", "green", "Green", "aqua", "Aqua", "red", "Red", "light_purple", "Light Purple", "yellow", "Yellow", "white", "White");
		}

		public static final BoolSetting COLORS = new BoolSetting("subtitleColorsEnabled", "Colors", "Each kind of sound in its own colour", true);
		public static final OptionSetting MUSIC = color("musicColor", "Music color", 6);
		public static final OptionSetting RECORDS = color("recordsColor", "Records color", 5);
		public static final OptionSetting WEATHER = color("weatherColor", "Weather color", 12);
		public static final OptionSetting BLOCKS = color("blocksColor", "Blocks color", 11);
		public static final OptionSetting HOSTILE = color("hostileColor", "Hostile color", 13);
		public static final OptionSetting NEUTRAL = color("neutralColor", "Neutral color", 15);
		public static final OptionSetting PLAYERS = color("playersColor", "Players color", 7);
		public static final OptionSetting AMBIENT = color("ambientColor", "Ambient color", 8);
		public static final OptionSetting VOICE = color("voiceColor", "Voice color", 14);

		// Nearby Sound Activity: the sounds around you, each with the way it came from, in the bottom-right corner
		// (control.feature.SoundActivity keeps them; control.hud.render.SoundActivityView draws them, on every line).
		/** The most rows the list can show (Maximum visible entries' top). */
		public static final int MOST_ENTRIES = 15;
		public static final BoolSetting ACTIVITY = new BoolSetting("nearbySoundActivity", "Nearby Sound Activity",
			"Shows nearby Minecraft sound events with directional indicators.", true);
		public static final BoolSetting ARROWS = new BoolSetting("activityArrows", "Directional arrows",
			"Which way each sound came from, as you face now", true);
		public static final BoolSetting BACKGROUND = new BoolSetting("activityBackground", "Subtitle background", true);
		public static final BoolSetting SHADOW = new BoolSetting("activityTextShadow", "Text shadow", true);
		public static final FloatSetting SCALE = new FloatSetting("activityScale", "Text scale", null, 1, 0.5F, 2, 0.05F, "×");
		public static final RgbSetting TEXT_COLOR = new RgbSetting("activityTextColor", "Text color", null, 0xFFFFFF);
		public static final BoolSetting KIND_COLORS = new BoolSetting("activityKindColors", "Color by kind of sound",
			"Each caption in its kind's color from Color below", false);
		public static final RgbSetting BACKGROUND_COLOR = new RgbSetting("activityBackgroundColor", "Background color", null, 0x000000);
		public static final IntSetting BACKGROUND_OPACITY = new IntSetting("activityBackgroundOpacity", "Background opacity", null, 50, 0, 100, 5,
			"%");
		public static final IntSetting RIGHT_PADDING = new IntSetting("activityRightPadding", "Right-edge padding",
			"From the right edge of the screen", 2, 0, 200, 1, "");
		public static final IntSetting BOTTOM_PADDING = new IntSetting("activityBottomPadding", "Bottom-edge padding",
			"From the bottom of the screen; the list rises above the hotbar where the two would meet", 30, 0, 300, 1, "");
		public static final IntSetting MAX_ENTRIES = new IntSetting("activityMaxEntries", "Maximum visible entries", null, 8, 1, MOST_ENTRIES, 1,
			"");
		public static final FloatSetting DURATION = new FloatSetting("activityDuration", "Display duration",
			"How long a sound stays after it was last heard", 3, 0.5F, 10, 0.5F, " s");
		public static final FloatSetting FADE = new FloatSetting("activityFade", "Fade duration", null, 0.6F, 0, 2, 0.1F, " s");
		/** How repeats of one sound share a row: one row per place (default), one per caption, or a row each. */
		public static final OptionSetting GROUPING = new OptionSetting("activityGrouping", "Duplicate-event grouping",
			"Same place: a sound repeating where it was refreshes its row, the same sound somewhere else gets its own", 0, "place", "Same place",
			"caption", "Same caption", "off", "Off");
		public static final IntSetting MAX_DISTANCE = new IntSetting("activityMaxDistance", "Maximum distance",
			"Farther sounds aren't shown (nor any beyond where Minecraft lets it be heard)", 48, 4, 128, 4, " blocks");
		public static final BoolSetting OWN_SOUNDS = new BoolSetting("activityOwnSounds", "Your own sounds",
			"Your own footsteps, eating and the rest you make", true);
		public static final BoolSetting INCLUDE_AMBIENT = new BoolSetting("activityAmbient", "Ambient sounds",
			"Weather, caves, water, lava, fire and the rest of the surroundings", true);
		public static final BoolSetting INCLUDE_BLOCKS = new BoolSetting("activityBlocks", "Block sounds",
			"Chests, doors, buttons, pistons, note blocks, blocks broken and placed", true);
		public static final BoolSetting INCLUDE_ENTITIES = new BoolSetting("activityEntities", "Entity sounds",
			"Mobs and animals: their calls, steps, hurts and deaths", true);
		public static final BoolSetting INCLUDE_COMBAT = new BoolSetting("activityCombat", "Combat sounds",
			"Attacks, players hurt, explosions, TNT, arrows, pearls, potions and other projectiles", true);
		public static final BoolSetting INCLUDE_PLAYERS = new BoolSetting("activityPlayers", "Player-action sounds",
			"Players' footsteps, eating, drinking, swimming and items used", true);

		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Subtitles",
				"Nearby sound activity with directions, and colored subtitles")
			.keywords("subtitles", "captions", "sounds", "accessibility", "color", "nearby sound activity", "audio subtitles", "footsteps",
				"directional")
			.icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Nearby Sound Activity", ACTIVITY, ARROWS, BACKGROUND, SHADOW)
			.section("Appearance", SCALE, TEXT_COLOR, KIND_COLORS, BACKGROUND_COLOR, BACKGROUND_OPACITY, RIGHT_PADDING, BOTTOM_PADDING, MAX_ENTRIES)
			.section("Behavior", DURATION, FADE, GROUPING, MAX_DISTANCE, OWN_SOUNDS)
			.advanced("Sound Categories", INCLUDE_AMBIENT, INCLUDE_BLOCKS, INCLUDE_ENTITIES, INCLUDE_COMBAT, INCLUDE_PLAYERS)
			.section("Color", COLORS, MUSIC, RECORDS, WEATHER, BLOCKS, HOSTILE, NEUTRAL, PLAYERS, AMBIENT, VOICE)
			.note("Nearby Sound Activity lists the sounds around you in the bottom-right corner, each with an arrow toward it, in "
				+ "Minecraft's own caption text and language. It reads only what the game plays: nothing is recorded or sent anywhere. "
				+ "While it's on it takes the place of Minecraft's subtitles; your Show Subtitles option is left as it is and applies "
				+ "again once it's off. Color colors Minecraft's subtitles (Options > Accessibility > Show Subtitles).")
			.build();

		private Subtitles() {
		}

		/** Why {@code setting} changes nothing as the module is set now, or null. Allocation-free. */
		public static @org.jspecify.annotations.Nullable String inactiveReason(com.flintclient.client.control.model.ModuleState module,
			SettingDescriptor<?> setting) {
			if (setting == MUSIC || setting == RECORDS || setting == WEATHER || setting == BLOCKS || setting == HOSTILE || setting == NEUTRAL
				|| setting == PLAYERS || setting == AMBIENT || setting == VOICE) {
				return module.bool(COLORS) || module.bool(ACTIVITY) && module.bool(KIND_COLORS) ? null : "Turn on Colors first";
			}
			if (setting == ACTIVITY || setting == COLORS || module.descriptor().indexOf(setting) < 0) {
				return null;
			}
			if (!module.bool(ACTIVITY)) {
				return "Turn on Nearby Sound Activity first";
			}
			if (setting == BACKGROUND_COLOR || setting == BACKGROUND_OPACITY) {
				return module.bool(BACKGROUND) ? null : "Turn on Subtitle background first";
			}
			return null;
		}
	}

	public static final class Hypixel {
		public static final String ID = "hypixel";
		public static final BoolSetting AUTO_FRIEND = new BoolSetting("autoFriendEnabled", "Auto friend", "Accepts friend requests for you", false);
		public static final BoolSetting FRIEND_MESSAGES = new BoolSetting("friendMessages", "Show friend messages",
			"Friends joining and leaving, in chat", true);
		public static final BoolSetting AUTO_TIP = new BoolSetting("autoTipEnabled", "Auto tip", "Tips every booster for you", true);
		public static final IntSetting TIP_INTERVAL = new IntSetting("autoTipInterval", "Interval", null, 900, 60, 3600, 60, " s");
		public static final BoolSetting AUTO_GG = new BoolSetting("autoGGEnabled", "Auto GG", "Says GG when a game ends", false);
		public static final IntSetting GG_DELAY = new IntSetting("autoGGDelay", "Delay", null, 20, 1, 100, 1, " ticks");
		public static final TextSetting GG_TEXT = new TextSetting("ggString", "GG text", "What is sent", "/ac gg", 64);
		public static final BoolSetting ANTI_GG = new BoolSetting("antiGGEnabled", "Anti GG", "Hides other players' GG messages", false);
		public static final BoolSetting ANTI_LOBBY_JOIN = new BoolSetting("antiLobbyJoinEnabled", "Anti lobby join",
			"Hides \"joined the lobby\" messages", false);
		public static final BoolSetting OOF = new BoolSetting("oofModEnabled", "Oof sound", "A sound when you get a kill", false);
		public static final IntSetting OOF_VOLUME = new IntSetting("volume", "Volume", null, 10, 0, 30, 1, "");
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Hypixel", "Handy automations for Hypixel")
			.keywords("hypixel", "auto gg", "tip", "friend", "lobby").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("Auto Friend", AUTO_FRIEND, FRIEND_MESSAGES).section("Auto Tip", AUTO_TIP, TIP_INTERVAL).section("Auto GG", AUTO_GG, GG_DELAY, GG_TEXT)
			.section("Anti GG", ANTI_GG).section("Anti Lobby Join", ANTI_LOBBY_JOIN).section("Oof Mod", OOF, OOF_VOLUME).needs(Requirement.WORLD)
			.note("Only acts on Hypixel (hypixel.net). Level Head needs a Hypixel API key, so Flint doesn't offer it.").build();
	}

	public static final class Discord {
		public static final String ID = "discord";
		public static final BoolSetting VERSION = new BoolSetting("showVersion", "Show version", true);
		public static final BoolSetting WORLD = new BoolSetting("showWorldName", "Show singleplayer world name",
			"Your world's name after \"Singleplayer\"; off shows only the version and Singleplayer", false);
		public static final BoolSetting DIMENSION = new BoolSetting("showDimension", "Show dimension", true);
		public static final BoolSetting BIOME = new BoolSetting("showBiome", "Show biome", false);
		public static final BoolSetting RIDING = new BoolSetting("showRiding", "Show riding", false);
		public static final BoolSetting SERVER = new BoolSetting("showServerName", "Show public server name",
			"Names well-known public servers (Hypixel, Minemen Club...). A server's address is never shown", true);
		public static final BoolSetting HYPIXEL_MODE = new BoolSetting("hypixelGamemode", "Show Hypixel gamemodes", true);
		// Simple Discord Rich Presence's options.
		public static final BoolSetting LOG_STATE = new BoolSetting("logState", "Log state",
			"Logs the state sent to Discord each time it changes", false);
		public static final TextSetting BUTTON1_LABEL = new TextSetting("button1Label", "Button 1 label",
			"A button on your Discord profile (Discord allows two); empty for none", "", 32);
		public static final TextSetting BUTTON1_URL = new TextSetting("button1Url", "Button 1 URL", "Where it opens (https://...)", "", 512);
		public static final TextSetting BUTTON2_LABEL = new TextSetting("button2Label", "Button 2 label", null, "", 32);
		public static final TextSetting BUTTON2_URL = new TextSetting("button2Url", "Button 2 URL", null, "", 512);
		public static final BoolSetting DIMENSION_PRESENCE = new BoolSetting("enableUpdateDimensionPresence", "Dimension presence",
			"Says which dimension you are in when you join a world or change dimension", true);
		public static final TextSetting DIMENSION_MESSAGE = new TextSetting("dimensionMessage", "Dimension message",
			"Variables: {{dimension.name}}, {{dimension.path}}, {{dimension.namespace}}, {{dimension.identifier}}, {{player.name}}, "
				+ "{{player.uuid}}", "{{dimension.name}}", 128);
		public static final BoolSetting DIMENSION_PREFIX = new BoolSetting("prefixWithIn", "Prefix with \"In\"", "\"In Overworld\" rather than "
			+ "\"Overworld\"", true);
		public static final BoolSetting SCREEN_PRESENCE = new BoolSetting("enableUpdateScreenPresence", "Screen presence",
			"Says you are on the main menu when one of the screens below opens", true);
		public static final TextSetting SCREENS = new TextSetting("screens", "Screens",
			"Screen classes, comma separated (Flint's own main menu counts as the title screen)",
			"net.minecraft.client.gui.screens.TitleScreen, net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen, "
				+ "net.minecraft.client.gui.screens.worldselection.SelectWorldScreen", 1000);
		public static final TextSetting SCREEN_MESSAGE = new TextSetting("screenMessage", "Screen message", null, "Main Menu", 128);
		public static final ModuleDescriptor DESCRIPTOR = ModuleDescriptor.builder(ID, "Discord", "Show what you're doing on Discord")
			.keywords("discord", "rich presence", "status", "activity", "sdrp").icon(IconId.GAUGE).category(Category.UTILITY)
			.section("General", VERSION, WORLD, DIMENSION, BIOME, RIDING, SERVER, HYPIXEL_MODE)
			.section("Simple Discord Rich Presence", DIMENSION_PRESENCE, DIMENSION_MESSAGE, DIMENSION_PREFIX, SCREEN_PRESENCE, SCREENS, SCREEN_MESSAGE,
				BUTTON1_LABEL, BUTTON1_URL, BUTTON2_LABEL, BUTTON2_URL, LOG_STATE)
			.note("Flint's app shows it on your Discord profile (Settings > Discord in the app must be on, with Discord open), under Flint's own "
				+ "Discord application.").build();
	}

	/** The batch above, for the registry. */
	public static List<ModuleDescriptor> all() {
		List<ModuleDescriptor> out = new ArrayList<>();
		out.add(AutoText.DESCRIPTOR);
		out.add(CullLogs.DESCRIPTOR);
		out.add(Backups.DESCRIPTOR);
		out.add(ItemDespawn.DESCRIPTOR);
		out.add(UhcOverlay.DESCRIPTOR);
		out.add(Elytras.DESCRIPTOR);
		out.add(Totem.DESCRIPTOR);
		out.add(Subtitles.DESCRIPTOR);
		out.add(Hypixel.DESCRIPTOR);
		out.add(Discord.DESCRIPTOR);
		return out;
	}

	static SettingDescriptor<?>[] none() {
		return new SettingDescriptor<?>[0];
	}
}
