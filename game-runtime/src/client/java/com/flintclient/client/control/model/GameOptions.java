package com.flintclient.client.control.model;

import java.util.List;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Flint's game options: client-wide settings that aren't a mod, shown on the Right Shift menu's General and Graphics tabs.
 * Each is always in effect (there is nothing to turn on); the values live in {@code control-center.json}'s "game" section
 * ({@code ControlSettings#game}). Pure data, free of Minecraft types.
 */
public final class GameOptions {
	public static final BoolSetting NO_HOTBAR_LOOPING = new BoolSetting("disableHotbarLooping", "Disable hotbar looping",
		"Scrolling stops at the first and last slot instead of wrapping around", false);
	public static final BoolSetting MINIMAL_BOBBING = new BoolSetting("minimalViewBobbing", "Minimal view bobbing",
		"With View Bobbing on, only your hand bobs as you walk; the camera stays level", false);
	public static final BoolSetting CLOCK_24H = new BoolSetting("clock24h", "24-hour clock", "The clock at the top of this menu (off: 3:57 PM)", false);
	public static final KeySetting LEAVE_KEY = new KeySetting("leaveKey", "Leave server", "Leaves the server or world you're in",
		KeySetting.NONE);
	public static final BoolSetting LEAVE_CONFIRM = new BoolSetting("leaveConfirmation", "Server leave confirmation",
		"Asks first when you leave a server (the pause menu or the key)", false);
	public static final BoolSetting MOD_KEYS = new BoolSetting("modKeys", "Toggle mod keybinds",
		"Off: no mod's own toggle key does anything", true);
	public static final IntSetting INACTIVE_FPS = new IntSetting("inactiveFps", "Inactive FPS", "The frame rate while unfocused or idle", 30,
		5, 120, 5, " FPS");
	public static final BoolSetting LIMIT_UNFOCUSED = new BoolSetting("limitUnfocused", "Limit when unfocused",
		"While another window is in front of Minecraft", false);
	public static final BoolSetting LIMIT_IDLE = new BoolSetting("limitIdle", "Limit when idle", "After no key or mouse input for a while",
		false);
	public static final IntSetting IDLE_MINUTES = new IntSetting("idleMinutes", "Idle minutes", null, 5, 0, 60, 1, " min");
	public static final IntSetting IDLE_SECONDS = new IntSetting("idleSeconds", "Idle seconds", null, 0, 0, 55, 5, " s");
	/**
	 * Smooth screen sharing (perf/GpuPriority): on Windows, while the game is in a window (Flint opens it maximized), its GPU work
	 * goes behind a screen capture's (Discord, OBS) when both want the GPU at once; in fullscreen and alone on the GPU nothing
	 * changes. Measured with shaders (GPU at 97%): a captured window's stream had 0.1-0.7% of frames off by more than 8 ms instead
	 * of 1.6-4.5%, at the same FPS; alone, no FPS cost.
	 */
	public static final BoolSetting SMOOTH_SHARING = new BoolSetting("smoothScreenSharing", "Smooth screen sharing",
		"Discord and OBS get the GPU before Minecraft while it runs in a window, so your stream stays smooth. For smooth streams, use VSync "
			+ "or Unlimited FPS instead of a 60-120 cap", true);
	/**
	 * Borderless fullscreen: Fullscreen (F11, Video Settings) is a borderless window over the whole monitor instead of exclusive
	 * fullscreen. Measured on 26.3: Windows' window capture (Discord's "share an application") got 1 frame in 12 s of exclusive
	 * fullscreen and every frame of borderless. On 26.x this is Minecraft's own Exclusive Fullscreen switch, kept in step both
	 * ways (control/platform/FullscreenSync); on 1.21.x and 1.8.9 Flint makes the window (FullscreenModeMixin, BorderlessWindow189).
	 */
	public static final BoolSetting BORDERLESS_FULLSCREEN = new BoolSetting("borderlessFullscreen", "Borderless fullscreen",
		"Fullscreen (F11) as a borderless window: instant Alt+Tab, and Discord or OBS see every frame. Off: exclusive fullscreen", true);

	/** Flint's menu key (Right Shift unless changed), as Minecraft's Controls keeps it (not kept here). */
	public static final KeySetting MENU_KEY = new KeySetting("menuKey", "Open Flint menu",
		"The same binding as in Minecraft's Controls, under Flint Client", "key.keyboard.right.shift");

	/** Flint's "Open HUD editor" key binding, as Minecraft's Controls keeps it (not kept here). */
	public static final KeySetting HUD_EDITOR_KEY = new KeySetting("hudEditorKey", "Open HUD editor",
		"The same binding as in Minecraft's Controls, under Flint Client", KeySetting.NONE);

	public static final BoolSetting MENU_SOUND = new BoolSetting("menuSound", "Open and close sound", "A soft sound as the menu opens and closes",
		true);
	/**
	 * The menu's size before 1.0.4 (a 0.5×–1.5× slider). Kept so the file still reads; nothing shows or uses it now: {@link #MENU_SIZE}
	 * took its place.
	 */
	public static final FloatSetting MENU_SCALE = new FloatSetting("menuScale", "UI scaling", "The Right Shift menu's size", 1, 0.5F, 1.5F,
		0.05F, "×");
	public static final ChoiceSetting<MenuSize> MENU_SIZE = new ChoiceSetting<>("menuSize", "Menu size",
		"The size of this menu and Flint's windows (Large is the size before 1.0.4)", MenuSize.class, MenuSize.MEDIUM);
	/**
	 * How much of the game shows through the Right Shift menu's panels, cards and top row over a live game: 0 solid, up to 50 %.
	 * Text stays readable at every step (MenuStyle lightens grey text as needed).
	 */
	public static final IntSetting MENU_TRANSPARENCY = new IntSetting("menuTransparency", "Menu transparency",
		"How much of the game shows through the menu (0: solid)", 30, 0, 50, 5, "%");
	/** How much the game behind the menu's panels is blurred (Minecraft's menu blur, kept to the panels): 0 off. */
	public static final IntSetting MENU_BLUR = new IntSetting("menuBlurStrength", "Menu blur strength",
		"How much the game behind the menu's panels is blurred (0: off)", 5, 0, 10, 1, "");
	public static final FloatSetting HUD_SCALE_STEP = new FloatSetting("hudScalingStep", "Scaling step",
		"How much one wheel notch resizes a HUD mod", 0.25F, 0.05F, 1, 0.05F, "×");

	public static final BoolSetting FLINT_BADGE = new BoolSetting("flintBadge", "Flint icon on nametags",
		"The Flint mark before the name of a player on Flint, on nametags and in the player list", true);
	/** Whether Flint's servers are told you are on Flint (control/feature/FlintPresence), so other Flint players see your mark. */
	public static final BoolSetting SHARE_PRESENCE = new BoolSetting("sharePresence", "Show I'm on Flint",
		"Other Flint players see your Flint mark while you play. Off, Flint's servers aren't told", true);

	/**
	 * Capes players equipped in the Flint Store (control/feature/StoreCapes), on you and on other Flint players. Off: only
	 * Minecraft's own capes are drawn, and the Store isn't asked about anyone.
	 */
	public static final BoolSetting STORE_CAPES = new BoolSetting("storeCapes", "Flint Store capes",
		"Capes equipped in the Flint Store, on you and other Flint players. Off: Minecraft's capes only, and the Store isn't asked", true);

	/**
	 * A Flint Store cape on the elytra (control/platform/Mc263CapeElytra): a player who wears one and an elytra has wings made of
	 * the cape's own design, animated with it, as the Flint Store previews them. Off: Minecraft's elytra. For everyone you see.
	 */
	public static final BoolSetting CAPE_ELYTRA = new BoolSetting("capeElytra", "Cape design on elytra",
		"An elytra shows the wearer's Flint Store cape, animated with it, as the Flint Store previews it. Off: Minecraft's elytra", true);

	/** Minecraft's own title screen (with a small Flint button to come back) instead of Flint's main menu. */
	public static final BoolSetting VANILLA_MAIN_MENU = new BoolSetting("vanillaMainMenu", "Vanilla main menu",
		"Minecraft's own title screen instead of Flint's; its Flint button switches back", false);

	/**
	 * Small notes in game (Minecraft's toasts) when a friend sends a message, asks to be friends, accepts a request or comes
	 * online, from the Flint app's Friends (module/social/FriendsClient). Switched on the Friends screen.
	 */
	public static final BoolSetting FRIEND_NOTICES = new BoolSetting("friendNotices", "Friends notifications in game",
		"A small note in game when a friend messages you, asks to be friends or comes online", true);

	/**
	 * The sizes of the main menu's parts, in percent of their design size (Utilities › Menu sizes, ui/screen/MenuSizesScreen):
	 * each part grows or shrinks about its own corner or centre, and gives way (smaller) where the window has no room for it.
	 * Minecraft's own title screen shows the switch and the cards at the same sizes.
	 */
	public static final IntSetting MENU_SIZE_LOGO = menuSize("menuSizeLogo", "FLINT logo");
	public static final IntSetting MENU_SIZE_BUTTONS = menuSize("menuSizeButtons", "Main buttons");
	public static final IntSetting MENU_SIZE_VERSION = menuSize("menuSizeVersion", "Version line");
	public static final IntSetting MENU_SIZE_REWARDS = menuSize("menuSizeRewards", "Daily Rewards card");
	public static final IntSetting MENU_SIZE_NEWS = menuSize("menuSizeNews", "What's New card");
	public static final IntSetting MENU_SIZE_SWITCH = menuSize("menuSizeSwitch", "Flint | Vanilla switch");
	public static final IntSetting MENU_SIZE_TOP_RIGHT = menuSize("menuSizeTopRight", "Account and icons");
	public static final IntSetting MENU_SIZE_DISCORD = menuSize("menuSizeDiscord", "Join our Discord card");
	public static final IntSetting MENU_SIZE_SHOWCASE = menuSize("menuSizeShowcase", "Showcase");
	/** The menu sizes, in the order Utilities › Menu sizes lists them. */
	public static final List<IntSetting> MENU_SIZES = List.of(MENU_SIZE_LOGO, MENU_SIZE_BUTTONS, MENU_SIZE_VERSION, MENU_SIZE_REWARDS,
		MENU_SIZE_NEWS, MENU_SIZE_SWITCH, MENU_SIZE_TOP_RIGHT, MENU_SIZE_DISCORD, MENU_SIZE_SHOWCASE);

	/** Every option, in the order the file keeps them. */
	public static final List<SettingDescriptor<?>> ALL = List.of(NO_HOTBAR_LOOPING, MINIMAL_BOBBING, CLOCK_24H, LEAVE_KEY, LEAVE_CONFIRM, MOD_KEYS,
		INACTIVE_FPS, LIMIT_UNFOCUSED, LIMIT_IDLE, IDLE_MINUTES, IDLE_SECONDS, SMOOTH_SHARING, BORDERLESS_FULLSCREEN, HUD_SCALE_STEP, MENU_SCALE, MENU_SIZE, MENU_TRANSPARENCY, MENU_BLUR, MENU_SOUND,
		FLINT_BADGE, SHARE_PRESENCE, STORE_CAPES, VANILLA_MAIN_MENU, FRIEND_NOTICES, CAPE_ELYTRA, MENU_SIZE_LOGO, MENU_SIZE_BUTTONS, MENU_SIZE_VERSION,
		MENU_SIZE_REWARDS, MENU_SIZE_NEWS, MENU_SIZE_SWITCH, MENU_SIZE_TOP_RIGHT, MENU_SIZE_DISCORD, MENU_SIZE_SHOWCASE);

	/** A main-menu part's size: 50 % to 200 % in steps of 10, 100 % (its design size) by default. */
	private static IntSetting menuSize(String id, String label) {
		return new IntSetting(id, label, "Its size on the main menu", 100, 50, 200, 10, "%");
	}

	private GameOptions() {
	}

	/** Why {@code setting} does nothing with the options as they are ({@code bool} reads one), or null. */
	public static @Nullable String inactiveReason(SettingDescriptor<?> setting, Predicate<BoolSetting> bool) {
		if (setting == IDLE_MINUTES || setting == IDLE_SECONDS) {
			return bool.test(LIMIT_IDLE) ? null : "Only with Limit when idle on";
		}
		if (setting == INACTIVE_FPS) {
			return bool.test(LIMIT_UNFOCUSED) || bool.test(LIMIT_IDLE) ? null : "Turn on a limit first";
		}
		return null;
	}

	/** How long without input counts as idle, in milliseconds (at least a second). */
	public static long idleMillis(int minutes, int seconds) {
		return Math.max(1, minutes * 60L + seconds) * 1000L;
	}
}
