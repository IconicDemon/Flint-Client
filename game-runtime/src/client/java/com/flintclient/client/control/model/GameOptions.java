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

	/** Flint's menu key (Right Shift unless changed), as Minecraft's Controls keeps it (not kept here). */
	public static final KeySetting MENU_KEY = new KeySetting("menuKey", "Open Flint menu",
		"The same binding as in Minecraft's Controls, under Flint Client", "key.keyboard.right.shift");

	/** Flint's "Open HUD editor" key binding, as Minecraft's Controls keeps it (not kept here). */
	public static final KeySetting HUD_EDITOR_KEY = new KeySetting("hudEditorKey", "Open HUD editor",
		"The same binding as in Minecraft's Controls, under Flint Client", KeySetting.NONE);

	public static final BoolSetting MENU_SOUND = new BoolSetting("menuSound", "Open and close sound", "A soft sound as the menu opens and closes",
		true);
	public static final FloatSetting MENU_SCALE = new FloatSetting("menuScale", "UI scaling", "The Right Shift menu's size", 1, 0.5F, 1.5F,
		0.05F, "×");
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

	/** Minecraft's own title screen (with a small Flint button to come back) instead of Flint's main menu. */
	public static final BoolSetting VANILLA_MAIN_MENU = new BoolSetting("vanillaMainMenu", "Vanilla main menu",
		"Minecraft's own title screen instead of Flint's; its Flint button switches back", false);

	/**
	 * Small notes in game (Minecraft's toasts) when a friend sends a message, asks to be friends, accepts a request or comes
	 * online, from the Flint app's Friends (module/social/FriendsClient). Switched on the Friends screen.
	 */
	public static final BoolSetting FRIEND_NOTICES = new BoolSetting("friendNotices", "Friends notifications in game",
		"A small note in game when a friend messages you, asks to be friends or comes online", true);

	/** Every option, in the order the file keeps them. */
	public static final List<SettingDescriptor<?>> ALL = List.of(NO_HOTBAR_LOOPING, MINIMAL_BOBBING, CLOCK_24H, LEAVE_KEY, LEAVE_CONFIRM, MOD_KEYS,
		INACTIVE_FPS, LIMIT_UNFOCUSED, LIMIT_IDLE, IDLE_MINUTES, IDLE_SECONDS, HUD_SCALE_STEP, MENU_SCALE, MENU_SOUND, FLINT_BADGE,
		SHARE_PRESENCE, STORE_CAPES, VANILLA_MAIN_MENU, FRIEND_NOTICES);

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
