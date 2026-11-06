package com.flintclient.client.platform;

import com.flintclient.client.control.feature.Features;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.Waypoint;
import com.flintclient.client.control.hud.CpsCounter;
import com.flintclient.client.control.hud.render.HudLooks;
import com.flintclient.client.control.hud.render.KeyLabels;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.HudMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.legacy.Flint;
import com.flintclient.legacy.mixin.GameGuiAccessor;
import com.flintclient.legacy.mods.Combat;
import com.flintclient.legacy.mods.ItemCounter;
import com.flintclient.legacy.shared.KeyNames189;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.gui.chat.ChatGui;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.options.ServerListEntry;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import org.jspecify.annotations.Nullable;

/**
 * What the HUD modules and the HUD editor read from the game, on Minecraft 1.8.9: the player and the world, the session, the keys
 * the keystrokes show, the item counts (the Item Counter backend's), the reach of your last hit (the Reach Display backend's),
 * the scoreboard's size (the Scoreboard backend's) and the chat's. What 1.8.9 doesn't have (a minimap, teammates' bearings, item
 * cooldowns, potion counts, the block and mob under the crosshair) reads as nothing: those modules aren't listed on 1.8.9.
 * Per-frame reads are field reads and allocate nothing. Platform class (see {@link Clock}). Render thread.
 */
public final class HudData {
	public static final int OVERWORLD = 0;
	public static final int NETHER = 1;
	public static final int OTHER = 2;
	public static final int ARROWS = 0;
	public static final int GOLDEN_APPLES = 1;
	public static final int PEARLS = 2;
	public static final int TOTEMS = 3;
	public static final int FIREWORKS = 4;
	public static final int BLOCKS = 5;
	public static final int GROUPS = 6;
	public static final int UNKNOWN_LATENCY = -1;
	public static final int TEAM_MAX = 24;

	private static final CpsCounter CPS = new CpsCounter();
	private static final KeyBinding[] KEYS = new KeyBinding[7];
	private static final int[] KEY_CODES = new int[7];
	private static final Object[] KEY_TOKENS = new Object[7];
	private static float partialTick;
	private static @Nullable String language;
	private static @Nullable ClientWorld biomeWorld;
	private static long biomeAt = Long.MIN_VALUE;
	private static @Nullable String biomeName;
	private static int loadedChunks;
	private static long loadedAt;
	private static int @Nullable [] sidebar;

	static {
		java.util.Arrays.fill(KEY_CODES, Integer.MIN_VALUE);
	}

	private HudData() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---------- the session ----------

	public static @Nullable Object player() {
		Minecraft mc = mc();
		return mc.world == null ? null : mc.player;
	}

	private static @Nullable LocalClientPlayerEntity p() {
		Minecraft mc = mc();
		return mc.world == null ? null : mc.player;
	}

	public static @Nullable Object level() {
		return mc().world;
	}

	public static boolean inWorld() {
		return mc().world != null && mc().player != null;
	}

	public static boolean multiplayer() {
		return mc().getNetworkHandler() != null && !mc().isIntegratedServerRunning();
	}

	/** 1.8.9 has no Network Core: the server's own report in the player list is the latency, as on Tab. */
	public static boolean networkCoreRunning() {
		return true;
	}

	public static boolean latencyKnown() {
		return latencyMillis() != UNKNOWN_LATENCY;
	}

	public static int latencyMillis() {
		Minecraft mc = mc();
		ClientPlayNetworkHandler handler = mc.getNetworkHandler();
		if (handler == null || mc.player == null || mc.isIntegratedServerRunning()) {
			return UNKNOWN_LATENCY;
		}
		PlayerInfo info = handler.getOnlinePlayer(mc.player.getUuid());
		return info == null ? UNKNOWN_LATENCY : info.getPing();
	}

	public static boolean pausing() {
		Minecraft mc = mc();
		return mc.isPaused() || mc.screen != null && mc.screen.shouldPauseGame();
	}

	public static boolean debugOverlayOpen() {
		return mc().options.debugEnabled;
	}

	/** 1.8.9's inventory lists the effects beside it whenever there are some. */
	public static boolean screenShowsEffects() {
		Minecraft mc = mc();
		return mc.screen instanceof net.minecraft.client.gui.screen.game.inventory.PlayerInventoryScreen && mc.player != null
			&& !mc.player.getStatusEffects().isEmpty();
	}

	public static float tickrate() {
		return 20.0F;
	}

	public static float partialTick() {
		return partialTick;
	}

	/** From the frame hook: how far this frame is between two ticks. */
	public static void setPartialTick(float value) {
		partialTick = value;
	}

	/** The game's translation of {@code key}, or {@code fallback} where 1.8.9 has none. */
	public static String translate(String key, String fallback) {
		String text = I18n.translate(key);
		return text.equals(key) ? fallback : text;
	}

	public static boolean hasTranslation(String key) {
		return !I18n.translate(key).equals(key);
	}

	public static Object language() {
		String now = mc().options.language;
		if (language == null || !language.equals(now)) {
			language = now;
		}
		return language;
	}

	/** The clicks per second counter, fed by the clicks Minecraft hands to gameplay. */
	public static CpsCounter cps() {
		return CPS;
	}

	public static long nowMillis() {
		return System.nanoTime() / 1_000_000L;
	}

	// ---------- the window ----------

	public static int windowWidth() {
		return mc().width;
	}

	public static int windowHeight() {
		return mc().height;
	}

	public static int guiWidth() {
		return (int) Math.ceil(mc().width / (double) Frame.windowGuiScale());
	}

	public static int guiHeight() {
		return (int) Math.ceil(mc().height / (double) Frame.windowGuiScale());
	}

	public static double guiScale() {
		return Frame.windowGuiScale();
	}

	// ---------- the player ----------

	public static double x() {
		LocalClientPlayerEntity p = p();
		return p == null ? Double.NaN : p.x;
	}

	public static double y() {
		LocalClientPlayerEntity p = p();
		return p == null ? Double.NaN : p.getShape().minY;
	}

	public static double z() {
		LocalClientPlayerEntity p = p();
		return p == null ? Double.NaN : p.z;
	}

	public static float yaw() {
		LocalClientPlayerEntity p = mc().player;
		return p == null ? Float.NaN : p.yaw;
	}

	public static float pitch() {
		LocalClientPlayerEntity p = mc().player;
		return p == null ? Float.NaN : p.pitch;
	}

	public static int minY() {
		return 0;
	}

	public static int maxY() {
		return 255;
	}

	public static float saturation() {
		LocalClientPlayerEntity p = mc().player;
		return p == null ? Float.NaN : p.getHungerManager().getSaturationLevel();
	}

	public static int food() {
		LocalClientPlayerEntity p = mc().player;
		return p == null ? -1 : p.getHungerManager().getFoodLevel();
	}

	public static boolean sprinting() {
		LocalClientPlayerEntity p = mc().player;
		return p != null && p.isSprinting();
	}

	public static boolean sneaking() {
		LocalClientPlayerEntity p = mc().player;
		return p != null && p.isSneaking();
	}

	public static boolean flying() {
		LocalClientPlayerEntity p = mc().player;
		return p != null && p.abilities.flying;
	}

	public static long dayTicks() {
		ClientWorld world = mc().world;
		return world == null ? -1 : world.getTimeOfDay();
	}

	public static @Nullable String serverAddress() {
		Minecraft mc = mc();
		if (mc.world == null) {
			return null;
		}
		ServerListEntry entry = mc.getCurrentServerEntry();
		if (entry != null) {
			return entry.ip;
		}
		return mc.isIntegratedServerRunning() ? "Singleplayer" : null;
	}

	public static boolean horse(boolean looking, double[] out) {
		return false;
	}

	public static @Nullable String biome() {
		LocalClientPlayerEntity p = p();
		ClientWorld world = mc().world;
		if (p == null || world == null) {
			return null;
		}
		int bx = (int) Math.floor(p.x);
		int by = (int) Math.floor(p.y);
		int bz = (int) Math.floor(p.z);
		long at = ((long) bx & 0x3FFFFFF) << 38 | ((long) by & 0xFFF) << 26 | (long) bz & 0x3FFFFFF;
		if (world != biomeWorld || at != biomeAt) {
			biomeWorld = world;
			biomeAt = at;
			Biome biome = world.getBiome(new BlockPos(bx, by, bz));
			biomeName = biome == null ? null : biome.name;
		}
		return biomeName;
	}

	/** The chunks the game holds, from its chunk cache's own count (read twice a second). */
	public static int loadedChunks() {
		ClientWorld world = mc().world;
		if (world == null) {
			return 0;
		}
		long now = System.nanoTime();
		if (now - loadedAt > 500_000_000L) {
			loadedAt = now;
			String info = world.getChunkSourceDebugInfo();
			int n = 0;
			boolean digits = false;
			for (int i = 0; i < info.length(); i++) {
				char c = info.charAt(i);
				if (c >= '0' && c <= '9') {
					n = n * 10 + (c - '0');
					digits = true;
				} else if (digits) {
					break;
				}
			}
			loadedChunks = n;
		}
		return loadedChunks;
	}

	public static int dimension() {
		LocalClientPlayerEntity p = p();
		if (p == null) {
			return OTHER;
		}
		return p.dimension == 0 ? OVERWORLD : p.dimension == -1 ? NETHER : OTHER;
	}

	/** The yaw toward each shown waypoint in this dimension (as Direction's compass dots), with its colour. */
	public static int waypointYaws(float[] yaws, int[] colors) {
		LocalClientPlayerEntity p = p();
		String dimension = World.dimension();
		int index = Mods.index(UtilityMods.Waypoints.ID);
		if (p == null || dimension == null || !Mods.on(index)) {
			return 0;
		}
		ModuleState s = Mods.state(index);
		List<Waypoint> all = Features.waypoints().all();
		int max = s.integer(UtilityMods.Waypoints.MAX_DISTANCE);
		boolean allSets = s.bool(UtilityMods.Waypoints.ALL_SETS);
		int n = 0;
		for (int i = 0; i < all.size() && n < yaws.length; i++) {
			Waypoint wp = all.get(i);
			if (!Features.waypoints().shown(wp, allSets) || !wp.dimension().equals(dimension)) {
				continue;
			}
			double dx = wp.x() + 0.5 - p.x;
			double dz = wp.z() + 0.5 - p.z;
			if (dx * dx + dz * dz < 1) {
				continue;
			}
			if (max > 0) {
				double dy = wp.y() - p.y;
				if (dx * dx + dy * dy + dz * dz > (double) max * max) {
					continue;
				}
			}
			yaws[n] = (float) Math.toDegrees(Math.atan2(-dx, dz));
			colors[n] = 0xFF000000 | wp.rgb();
			n++;
		}
		return n;
	}

	public static int teamBearings(float[] bearings, int[] colors) {
		return 0;
	}

	// ---------- the keystrokes ----------

	private static KeyBinding keystroke(int index) {
		net.minecraft.client.options.GameOptions o = mc().options;
		switch (index) {
			case 0:
				return o.forwardKey;
			case 1:
				return o.leftKey;
			case 2:
				return o.backKey;
			case 3:
				return o.rightKey;
			case 4:
				return o.attackKey;
			case 5:
				return o.useKey;
			default:
				return o.jumpKey;
		}
	}

	/** A token that changes only when the key is rebound. */
	public static Object keystrokeBinding(int index) {
		int code = keystroke(index).getKeyCode();
		if (code != KEY_CODES[index] || KEY_TOKENS[index] == null) {
			KEY_CODES[index] = code;
			KEY_TOKENS[index] = new Object();
		}
		return KEY_TOKENS[index];
	}

	public static String keystrokeLabel(int index) {
		int code = keystroke(index).getKeyCode();
		if (code == 0) {
			return KeyLabels.UNBOUND;
		}
		if (code < 0) {
			return KeyLabels.mouse(code + 100);
		}
		return KeyLabels.keyboard(Keys.displayName(KeyNames189.name(code)));
	}

	public static boolean keystrokeDown(int index) {
		return Flint.physicallyDown(keystroke(index));
	}

	// ---------- items ----------

	public static @Nullable Object inventoryItem(int slot) {
		LocalClientPlayerEntity p = mc().player;
		return p == null ? null : p.inventory.getItem(slot);
	}

	public static int groupCount(int group) {
		return ItemCounter.groupCount(group);
	}

	public static @Nullable Object groupIcon(int group) {
		return ItemCounter.groupIcon(group);
	}

	public static int setExtraItems(String text) {
		return ItemCounter.setExtraItems(text);
	}

	public static int extraCount(int index) {
		return ItemCounter.extraCount(index);
	}

	public static @Nullable Object extraIcon(int index) {
		return ItemCounter.extraIcon(index);
	}

	/** 1.8.9 items have no cooldowns. */
	public static int cooldowns(Object[] stacks, float[] share, int[] ticksLeft, int max, float partialTick) {
		return 0;
	}

	// ---------- under the crosshair, in hand: not on 1.8.9 ----------

	public static HudLooks.@Nullable Block block(int range) {
		return null;
	}

	public static HudLooks.@Nullable Target target(int range) {
		return null;
	}

	public static HudLooks.@Nullable Held held() {
		return null;
	}

	public static int jumpResult() {
		return 0;
	}

	public static long jumpResultAt() {
		return 0;
	}

	public static float viewYaw() {
		return yaw();
	}

	public static float viewPitch() {
		return pitch();
	}

	/**
	 * Where sounds are heard from and which way the view faces (Nearby Sound Activity's arrows): 1.8.9 hears from the player,
	 * and in the front-facing third person the screen faces the other way, so its left and right swap. False outside a world.
	 */
	public static boolean soundListener(double[] out) {
		LocalClientPlayerEntity p = mc().player;
		if (p == null) {
			return false;
		}
		out[0] = p.x;
		out[1] = p.y;
		out[2] = p.z;
		out[3] = mc().options.perspective == 2 ? p.yaw + 180 : p.yaw;
		return true;
	}

	/** Armor's Push subtitles: 1.8.9 has no subtitles of its own and its Armor doesn't push them. */
	public static int subtitlesPush() {
		return 0;
	}

	public static HudLooks.@Nullable Pack topPack() {
		return null;
	}

	// ---------- the original mods' readings ----------

	/** Reach Display's distance: 0 before any hit, the last one (kept, or until Reset after has passed since it). */
	public static double reachShown(ModuleState state) {
		double reach = Combat.lastReach();
		if (reach < 0) {
			return 0.0;
		}
		if (state.bool(HudMods.ReachDisplay.KEEP_LAST)) {
			return reach;
		}
		double elapsed = (System.currentTimeMillis() - Combat.lastReachAt()) / 1000.0;
		return elapsed > state.decimal(HudMods.ReachDisplay.RESET_AFTER) ? 0.0 : reach;
	}

	public static int playTimeTicks() {
		return -1;
	}

	public static String statTime(int ticks) {
		double hours = ticks / 20.0 / 3600.0;
		return String.format(java.util.Locale.ROOT, "%.2f h", hours);
	}

	public static List<HudLooks.PotionRow> potions(ModuleState state) {
		return Collections.emptyList();
	}

	/** Whether the action bar shows a message right now. */
	public static boolean actionBarShowing() {
		Minecraft mc = mc();
		return mc.gui != null && ((GameGuiAccessor) mc.gui).flint$overlayMessageCooldown() > 0;
	}

	// ---------- Minecraft's own HUD ----------

	public static int chatWidth() {
		return ChatGui.getWidth(mc().options.chatWidth);
	}

	public static int chatHeight() {
		return ChatGui.getHeight(mc().options.unfocusedChatHeight);
	}

	public static float chatScale() {
		return mc().options.chatScale;
	}

	/** The scoreboard on the right as {@code {widest line, lines}} (the Scoreboard backend's, or Minecraft's own measure), or null. */
	public static int @Nullable [] sidebar() {
		return sidebar;
	}

	public static int @Nullable [] sidebarCached() {
		return sidebar;
	}

	/** Set by the Scoreboard backend each time it reads the sidebar (null: none shown). */
	public static void setSidebar(int @Nullable [] size) {
		sidebar = size;
	}

	public static int bossBars() {
		return net.minecraft.client.gui.BossBar.timer > 0 && net.minecraft.client.gui.BossBar.name != null ? 1 : 0;
	}

	/** 1.8.9 has no effect icons on its HUD. */
	public static int[] effectIconRows() {
		return new int[] {0, 0};
	}

	public static boolean harmfulIconsShown() {
		return false;
	}

	public static int armorEffectsPush(Frame frame) {
		return 0;
	}

	/**
	 * Armor in the HUD editor: its box (strip, text and warning signs) into {@code out} as {left, top, width, height, anchored}, and
	 * false when it shows nothing (with {@code placeholder}, the box of four empty slots). See {@code ArmorStatus.box}.
	 */
	public static boolean armorBox(float[] out, boolean placeholder) {
		return Flint.ready() && Flint.modules().armorStatus.box(out, placeholder);
	}
}
