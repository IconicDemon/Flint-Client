package com.flintclient.client.platform;

import com.flintclient.client.control.ControlCenter;
import com.flintclient.client.control.GameState;
import com.flintclient.client.control.hud.CpsCounter;
import com.flintclient.client.control.hud.render.HudLooks;
import com.flintclient.client.control.hud.render.KeyLabels;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.platform.Mc263ArmorHud;
import com.flintclient.client.control.platform.Mc263ControlPlatform;
import com.flintclient.client.control.platform.Mc263GameData;
import com.flintclient.client.control.platform.Mc263HudData;
import com.flintclient.client.control.platform.Mc263HudExtras;
import com.flintclient.client.control.platform.Mc263OriginalHud;
import com.flintclient.client.control.platform.Mc263Team;
import com.flintclient.client.network.NetworkCore;
import com.flintclient.client.network.NetworkPlatform;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.locale.Language;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What the HUD modules and the HUD editor read from the game: the player and the world, the session, the keys the keystrokes
 * show, the item counts, what is under the crosshair, the chat's and the scoreboard's place. Numbers, text and the engine's own
 * objects held as {@link Object} (an item to hand to {@link ItemPainter}, a player or world compared by identity to notice a
 * change). Per-frame reads are field reads and allocate nothing. Platform class (see {@link Clock}). Render thread.
 */
public final class HudData {
	/** Dimensions {@link #dimension()} tells apart: the two with a coordinate relation, and the rest. */
	public static final int OVERWORLD = Mc263HudData.OVERWORLD;
	public static final int NETHER = Mc263HudData.NETHER;
	public static final int OTHER = Mc263HudData.OTHER;
	/** Item Counter's groups, in display order. */
	public static final int ARROWS = Mc263GameData.ARROWS;
	public static final int GOLDEN_APPLES = Mc263GameData.GOLDEN_APPLES;
	public static final int PEARLS = Mc263GameData.PEARLS;
	public static final int TOTEMS = Mc263GameData.TOTEMS;
	public static final int FIREWORKS = Mc263GameData.FIREWORKS;
	public static final int BLOCKS = Mc263GameData.BLOCKS;
	public static final int GROUPS = Mc263GameData.GROUPS;
	/** The latency when none is known. */
	public static final int UNKNOWN_LATENCY = NetworkPlatform.UNKNOWN;
	/** Teammates Team Tracker shows at most. */
	public static final int TEAM_MAX = Mc263Team.MAX;

	private static final ItemStack[] COOLDOWN_STACKS = new ItemStack[32];
	private static final HudLooks.Block BLOCK = new HudLooks.Block();
	private static final HudLooks.Target TARGET = new HudLooks.Target();
	private static final HudLooks.Held HELD = new HudLooks.Held();

	private HudData() {
	}

	// ---------- the session ----------

	/** The local player (compared by identity: a new one after a respawn or a new world), or null outside a world. */
	public static @Nullable Object player() {
		return Mc263HudData.player();
	}

	/** The world the player is in (compared by identity; never kept), or null outside one. */
	public static @Nullable Object level() {
		return Mc263HudData.level();
	}

	/** A world is loaded and the player is in it. */
	public static boolean inWorld() {
		return GameState.inWorld();
	}

	/** Playing on a server rather than in singleplayer. */
	public static boolean multiplayer() {
		return GameState.multiplayer();
	}

	public static boolean networkCoreRunning() {
		return GameState.networkCoreRunning();
	}

	public static boolean latencyKnown() {
		return GameState.latencyKnown();
	}

	/** The server-reported latency in milliseconds, or {@link #UNKNOWN_LATENCY}. */
	public static int latencyMillis() {
		return NetworkCore.stats().latencyMillis();
	}

	/** Whether a pausing screen is open (the pause menu, a loading overlay that pauses). */
	public static boolean pausing() {
		return Mc263HudData.pausing();
	}

	/** Whether the F3 debug overlay is open. */
	public static boolean debugOverlayOpen() {
		return Mc263HudData.debugOverlayOpen();
	}

	/** Whether the open screen lists the player's effects itself (the inventory with room beside it). */
	public static boolean screenShowsEffects() {
		return Mc263HudData.screenShowsEffects();
	}

	/** The world's ticks per second (20 unless a server changed it). */
	public static float tickrate() {
		return Mc263HudData.tickrate();
	}

	/** How far this frame is between two game ticks (0..1), as the game's own HUD reads it. */
	public static float partialTick() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** The game's translation of {@code key}, or {@code fallback}; and the language itself, compared by identity to notice a change. */
	public static String translate(String key, String fallback) {
		return Language.getInstance().getOrDefault(key, fallback);
	}

	public static boolean hasTranslation(String key) {
		return Language.getInstance().has(key);
	}

	public static Object language() {
		return Language.getInstance();
	}

	/** The clicks per second counter (the game's clicks), and the clock it is fed from. */
	public static CpsCounter cps() {
		return ControlCenter.cps();
	}

	public static long nowMillis() {
		return ControlCenter.nowMillis();
	}

	// ---------- the window ----------

	/** The game window in pixels, and in GUI units (the GUI scale applied). */
	public static int windowWidth() {
		return Minecraft.getInstance().getWindow().getWidth();
	}

	public static int windowHeight() {
		return Minecraft.getInstance().getWindow().getHeight();
	}

	public static int guiWidth() {
		return Minecraft.getInstance().getWindow().getGuiScaledWidth();
	}

	public static int guiHeight() {
		return Minecraft.getInstance().getWindow().getGuiScaledHeight();
	}

	/** The GUI scale as the game sets it (a whole number on every line), unclamped. */
	public static double guiScale() {
		return Minecraft.getInstance().getWindow().getGuiScale();
	}

	// ---------- the player ----------

	/** The player's position (NaN outside a world). */
	public static double x() {
		LocalPlayer p = Mc263HudData.player();
		return p == null ? Double.NaN : p.getX();
	}

	public static double y() {
		LocalPlayer p = Mc263HudData.player();
		return p == null ? Double.NaN : p.getY();
	}

	public static double z() {
		LocalPlayer p = Mc263HudData.player();
		return p == null ? Double.NaN : p.getZ();
	}

	/** The player's yaw as the game keeps it (0 faces south, 90 west; not wrapped), or NaN outside a world. */
	public static float yaw() {
		return Mc263GameData.yaw();
	}

	public static float pitch() {
		return Mc263GameData.pitch();
	}

	/** The lowest and the highest buildable y of the dimension. */
	public static int minY() {
		return Mc263GameData.minY();
	}

	public static int maxY() {
		return Mc263GameData.maxY();
	}

	public static float saturation() {
		return Mc263GameData.saturation();
	}

	public static int food() {
		return Mc263GameData.food();
	}

	public static boolean sprinting() {
		return Mc263GameData.sprinting();
	}

	public static boolean sneaking() {
		return Mc263GameData.sneaking();
	}

	public static boolean flying() {
		return Mc263GameData.flying();
	}

	/** The overworld clock in ticks, or -1 outside a world. */
	public static long dayTicks() {
		return Mc263GameData.dayTicks();
	}

	/** The server's address, "Singleplayer" in a local world, or null outside a world. */
	public static @Nullable String serverAddress() {
		return Mc263GameData.serverAddress();
	}

	/** The horse ridden (else, when {@code looking}, the one under the crosshair): speed, jump, health, max health. */
	public static boolean horse(boolean looking, double[] out) {
		return Mc263GameData.horse(looking, out);
	}

	/** The biome's name where the player stands, in the game's language, or null. */
	public static @Nullable String biome() {
		LocalPlayer p = Mc263HudData.player();
		return p == null ? null : Mc263HudData.biome(p);
	}

	public static int loadedChunks() {
		return Mc263HudData.loadedChunks();
	}

	/** {@link #OVERWORLD}, {@link #NETHER} or {@link #OTHER}. */
	public static int dimension() {
		return Mc263HudData.dimension();
	}

	/** The yaw toward each shown waypoint in this dimension, with its colour; returns how many. */
	public static int waypointYaws(float[] yaws, int[] colors) {
		LocalPlayer p = Mc263HudData.player();
		return p == null ? 0 : Mc263HudData.waypointYaws(p, yaws, colors);
	}

	/** The bearing and colour of each teammate Team Tracker shows; returns how many. */
	public static int teamBearings(float[] bearings, int[] colors) {
		return Mc263Team.bearings(bearings, colors);
	}

	// ---------- the keystrokes ----------

	private static KeyMapping keystroke(int index) {
		return Mc263HudData.keystroke(index);
	}

	/** What key {@code index} (forward, left, back, right, attack, use, jump) is bound to, compared by identity to notice a change. */
	public static Object keystrokeBinding(int index) {
		return Mc263ControlPlatform.boundKey(keystroke(index));
	}

	/** The key's short label for the keystrokes ("W", "LMB", "SPACE"). */
	public static String keystrokeLabel(int index) {
		KeyMapping mapping = keystroke(index);
		if (mapping.isUnbound()) {
			return KeyLabels.UNBOUND;
		}
		InputConstants.Key key = Mc263ControlPlatform.boundKey(mapping);
		if (key.getType() == InputConstants.Type.MOUSE) {
			return KeyLabels.mouse(key.getValue());
		}
		return KeyLabels.keyboard(Mc263ControlPlatform.keyName(mapping).getString());
	}

	/** Whether the key is held by the player's own input. */
	public static boolean keystrokeDown(int index) {
		return Mc263ControlPlatform.physicallyDown(keystroke(index));
	}

	// ---------- items ----------

	/** The item in inventory slot {@code slot} (the engine's item stack), or null outside a world. */
	public static @Nullable Object inventoryItem(int slot) {
		LocalPlayer player = Minecraft.getInstance().player;
		return player == null ? null : player.getInventory().getItem(slot);
	}

	public static int groupCount(int group) {
		return Mc263GameData.groupCount(group);
	}

	public static Object groupIcon(int group) {
		return Mc263GameData.groupIcon(group);
	}

	/** Sets Item Counter's "More items" text; returns how many items it names. */
	public static int setExtraItems(String text) {
		return Mc263GameData.setExtraItems(text);
	}

	public static int extraCount(int index) {
		return Mc263GameData.extraCount(index);
	}

	public static Object extraIcon(int index) {
		return Mc263GameData.extraIcon(index);
	}

	/** The distinct items cooling down: their stacks, the share left and the ticks left (-1 unknown); returns how many. */
	public static int cooldowns(Object[] stacks, float[] share, int[] ticksLeft, int max, float partialTick) {
		int n = Mc263GameData.cooldowns(COOLDOWN_STACKS, share, ticksLeft, Math.min(max, COOLDOWN_STACKS.length), partialTick);
		for (int i = 0; i < n; i++) {
			stacks[i] = COOLDOWN_STACKS[i];
			COOLDOWN_STACKS[i] = null;
		}
		return n;
	}

	// ---------- under the crosshair, in hand ----------

	/** The block looked at within {@code range} blocks, or null. */
	public static HudLooks.@Nullable Block block(int range) {
		Mc263HudExtras.BlockLook look = Mc263HudExtras.block(range);
		if (look == null) {
			return null;
		}
		BLOCK.icon = look.icon;
		BLOCK.name = look.name;
		BLOCK.percent = look.percent;
		return BLOCK;
	}

	/** The mob looked at within {@code range} blocks, or null. */
	public static HudLooks.@Nullable Target target(int range) {
		Mc263HudExtras.TargetLook look = Mc263HudExtras.target(range);
		if (look == null) {
			return null;
		}
		TARGET.id = look.id;
		TARGET.name = look.name;
		TARGET.health = look.health;
		TARGET.maxHealth = look.maxHealth;
		TARGET.absorption = look.absorption;
		TARGET.armor = look.armor;
		return TARGET;
	}

	/** The item just taken in hand, or null with nothing in hand. */
	public static HudLooks.@Nullable Held held() {
		Mc263HudExtras.HeldLook look = Mc263HudExtras.held();
		if (look == null) {
			return null;
		}
		HELD.lines = look.lines;
		HELD.colors = look.colors;
		HELD.count = look.count;
		HELD.countColor = look.countColor;
		HELD.since = look.since;
		HELD.revision = look.revision;
		return HELD;
	}

	/** Jump Reset's last result in ticks (negative: early) and when it was ({@link System#nanoTime()}; 0: none yet). */
	public static int jumpResult() {
		return Mc263HudExtras.jumpResult();
	}

	public static long jumpResultAt() {
		return Mc263HudExtras.jumpResultAt();
	}

	/** The player's yaw and pitch as of this frame (Mousestrokes, Team Tracker), NaN with no player. */
	public static float viewYaw() {
		return Mc263HudExtras.yaw();
	}

	public static float viewPitch() {
		return Mc263HudExtras.pitch();
	}

	/**
	 * Where sounds are heard from and which way it faces (Nearby Sound Activity's arrows): the camera's x, y, z and yaw into
	 * {@code out} (third person included, as the sound engine hears it); false with no camera yet. Allocation-free.
	 */
	public static boolean soundListener(double[] out) {
		return com.flintclient.client.control.platform.Mc263SoundActivity.listener(out);
	}

	/** How far subtitles move up out of Armor's way (its Bottom anchor on the right, with Push subtitles), in GUI units. */
	public static int subtitlesPush() {
		Minecraft mc = Minecraft.getInstance();
		return Mc263ArmorHud.subtitlesPush(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
	}

	/** Pack Display's pack: the top selected pack of your own, or null. */
	public static HudLooks.@Nullable Pack topPack() {
		return Mc263HudExtras.topPack();
	}

	// ---------- the original mods' readings ----------

	/** The reach Reach Display shows now. */
	public static double reachShown(ModuleState state) {
		return Mc263OriginalHud.reachShown(state);
	}

	/** Your playtime statistic in ticks, or -1 before the server sent it. */
	public static int playTimeTicks() {
		return Mc263OriginalHud.playTimeTicks();
	}

	/** Ticks in the statistics screen's time format. */
	public static String statTime(int ticks) {
		return Mc263OriginalHud.statTime(ticks);
	}

	/** Potion Counter's rows. */
	public static List<HudLooks.PotionRow> potions(ModuleState state) {
		return Mc263OriginalHud.potions(state);
	}

	/** Whether the action bar shows a message right now. */
	public static boolean actionBarShowing() {
		return Mc263OriginalHud.actionBarShowing();
	}

	// ---------- Minecraft's own HUD, for the editor's guides and the default corners ----------

	public static int chatWidth() {
		return Mc263HudData.chatWidth();
	}

	public static int chatHeight() {
		return Mc263HudData.chatHeight();
	}

	public static float chatScale() {
		return Mc263HudData.chatScale();
	}

	/** The scoreboard on the right as {@code {widest line, lines}}, or null; allocates (the editor, once a second). */
	public static int @Nullable [] sidebar() {
		return Mc263HudData.sidebar();
	}

	/** {@link #sidebar()} worked out at most four times a second. */
	public static int @Nullable [] sidebarCached() {
		return Mc263HudData.sidebarCached();
	}

	public static int bossBars() {
		return Mc263HudData.bossBars();
	}

	/** Minecraft's own effect icons in its top-right rows: {@code {helpful, harmful}}; allocates. */
	public static int[] effectIconRows() {
		return Mc263HudData.effectIconRows();
	}

	/** Whether Minecraft draws its second line of effect icons (a harmful effect that shows its icon). */
	public static boolean harmfulIconsShown() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		for (MobEffectInstance effect : player.getActiveEffects()) {
			if (effect.showIcon() && !effect.getEffect().value().isBeneficial()) {
				return true;
			}
		}
		return false;
	}

	/** How far Armor pushes Minecraft's effect icons down (GUI units). */
	public static int armorEffectsPush(Frame frame) {
		return Mc263ArmorHud.effectsPush(frame.graphics());
	}

	/**
	 * Armor in the HUD editor: its box (strip, text and warning signs) into {@code out} as {left, top, width, height, anchored}, and
	 * false when it shows nothing (with {@code placeholder}, the box of four empty slots). See {@code Mc263ArmorHud.box}.
	 */
	public static boolean armorBox(float[] out, boolean placeholder) {
		return Mc263ArmorHud.box(out, placeholder);
	}
}
