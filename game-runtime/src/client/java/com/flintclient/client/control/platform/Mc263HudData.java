package com.flintclient.client.control.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft 26.3's side of what the HUD modules and the HUD editor read: the player, the world's tick rate
 * and dimension, the movement and mouse key mappings, whether the F3 overlay is open, and where the chat and
 * scoreboard sit. The per-frame reads are field reads and allocate nothing; {@link #sidebar} and
 * {@link #effectIconRows} walk collections (and allocate), so the editor calls them at most once a second.
 * Render thread.
 */
public final class Mc263HudData {
	/** Dimensions {@link #dimension()} tells apart: the two with a coordinate relation, and the rest. */
	public static final int OVERWORLD = 0;
	public static final int NETHER = 1;
	public static final int OTHER = 2;

	/** {@code Hud.SCORE_DISPLAY_ORDER}: highest score first, then by name ignoring case. */
	private static final Comparator<PlayerScoreEntry> SCORE_ORDER = Comparator.comparing(PlayerScoreEntry::value).reversed()
		.thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

	private Mc263HudData() {
	}

	/** The local player, or null outside a world. */
	public static @Nullable LocalPlayer player() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? null : minecraft.player;
	}

	/** The world the player is in, or null outside one (compared by identity to notice a world change; never kept). */
	public static @Nullable ClientLevel level() {
		return Minecraft.getInstance().level;
	}

	/**
	 * Whether a pausing screen is open (the pause menu, or a loading overlay that pauses): the game is paused in
	 * singleplayer, and in multiplayer the player is in the menu. Closing it is "resuming" ({@code Gui.isPausing}).
	 */
	public static boolean pausing() {
		return Minecraft.getInstance().gui.isPausing();
	}

	/**
	 * Whether the F3 debug overlay is open. Only the overlay itself counts, not debug lines a player has set to
	 * always show: those are part of their HUD and must not hide Flint's for good.
	 */
	public static boolean debugOverlayOpen() {
		return Minecraft.getInstance().debugEntries.isOverlayVisible();
	}

	/**
	 * Whether the open screen lists the player's effects itself (the inventory, when there is room beside it),
	 * which is when Minecraft leaves its own effect icons off the HUD ({@code Hud.extractEffects}). A field
	 * read and a little arithmetic.
	 */
	public static boolean screenShowsEffects() {
		Screen screen = Minecraft.getInstance().gui.screen();
		return screen != null && screen.showsActiveEffects();
	}

	/** The world's ticks per second (20 unless a server changed it), for effect timers. */
	public static float tickrate() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? 20.0F : level.tickRateManager().tickrate();
	}

	private static @Nullable ClientLevel biomeLevel;
	private static long biomeAt = Long.MIN_VALUE;
	private static @Nullable String biomeName;

	/** The biome's name where {@code player} stands, in the game's language; looked up again only after moving to another block. */
	public static @Nullable String biome(LocalPlayer player) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return null;
		}
		long at = player.blockPosition().asLong();
		if (level != biomeLevel || at != biomeAt) {
			biomeLevel = level;
			biomeAt = at;
			biomeName = level.getBiome(player.blockPosition()).unwrapKey()
				.map(key -> Component.translatable(key.identifier().toLanguageKey("biome")).getString()).orElse(null);
		}
		return biomeName;
	}

	/**
	 * The yaw toward each shown waypoint in this dimension (Minecraft's: 0 faces south, 90 west), with its colour, into
	 * the arrays; returns how many (at most their length).
	 */
	public static int waypointYaws(LocalPlayer player, float[] yaws, int[] colors) {
		String dimension = Mc263Features.dimension();
		if (dimension == null || !Mc263Features.markersShown()) {
			// Waypoints off, or their markers hidden with the toggle key: no dots either.
			return 0;
		}
		java.util.List<com.flintclient.client.control.feature.Waypoint> all = com.flintclient.client.control.feature.Features.waypoints().all();
		int max = com.flintclient.client.control.feature.Mods.state(com.flintclient.client.control.feature.Mods.index(
			com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ID)).integer(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.MAX_DISTANCE);
		int n = 0;
		boolean allSets = com.flintclient.client.control.feature.Mods.state(com.flintclient.client.control.feature.Mods.index(
			com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ID)).bool(com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ALL_SETS);
		var store = com.flintclient.client.control.feature.Features.waypoints();
		for (int i = 0; i < all.size() && n < yaws.length; i++) {
			com.flintclient.client.control.feature.Waypoint wp = all.get(i);
			if (!store.shown(wp, allSets) || !wp.dimension().equals(dimension)) {
				continue;
			}
			double dx = wp.x() + 0.5 - player.getX();
			double dz = wp.z() + 0.5 - player.getZ();
			if (dx * dx + dz * dz < 1) {
				continue;
			}
			if (max > 0) {
				double dy = wp.y() - player.getY();
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

	/** How many chunks the game holds (0 outside a world). */
	public static int loadedChunks() {
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? 0 : level.getChunkSource().getLoadedChunksCount();
	}

	/** {@link #OVERWORLD}, {@link #NETHER} or {@link #OTHER} (also outside a world). */
	public static int dimension() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return OTHER;
		}
		if (level.dimension() == Level.OVERWORLD) {
			return OVERWORLD;
		}
		return level.dimension() == Level.NETHER ? NETHER : OTHER;
	}

	/**
	 * The keystrokes module's mappings, in its order: forward, left, back, right, attack, use, jump. Read from
	 * the options each time (a field read each): the mappings can be rebound, never replaced.
	 */
	public static KeyMapping keystroke(int index) {
		Options options = Minecraft.getInstance().options;
		return switch (index) {
			case 0 -> options.keyUp;
			case 1 -> options.keyLeft;
			case 2 -> options.keyDown;
			case 3 -> options.keyRight;
			case 4 -> options.keyAttack;
			case 5 -> options.keyUse;
			default -> options.keyJump;
		};
	}

	/** The chat's full width in GUI units before scaling ({@code ChatComponent.getWidth} of the option). */
	public static int chatWidth() {
		return ChatComponent.getWidth(Minecraft.getInstance().options.chatWidth().get());
	}

	/** The chat's height while not typing ({@code ChatComponent.getHeight} of the unfocused option). */
	public static int chatHeight() {
		return ChatComponent.getHeight(Minecraft.getInstance().options.chatHeightUnfocused().get());
	}

	public static float chatScale() {
		return Minecraft.getInstance().options.chatScale().get().floatValue();
	}

	/**
	 * The scoreboard shown on the right, as {@code {widest line in GUI units, lines}}, or null when none is
	 * shown. Works it out as {@code Hud.displayScoreboardSidebar} does: the team colour's objective first, at
	 * most 15 scores, names starting with {@code #} hidden. Allocates; for the editor, at most once a second.
	 */
	public static int @Nullable [] sidebar() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level == null || player == null) {
			return null;
		}
		Scoreboard scoreboard = level.getScoreboard();
		Objective objective = null;
		PlayerTeam team = scoreboard.getPlayersTeam(player.getScoreboardName());
		if (team != null) {
			DisplaySlot slot = com.flintclient.client.compat.McCompat.teamSidebar(team);
			if (slot != null) {
				objective = scoreboard.getDisplayObjective(slot);
			}
		}
		if (objective == null) {
			objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		}
		if (objective == null) {
			return null;
		}
		List<PlayerScoreEntry> shown = new ArrayList<>();
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			if (!entry.isHidden()) {
				shown.add(entry);
			}
		}
		shown.sort(SCORE_ORDER);
		int lines = Math.min(shown.size(), 15);
		Font font = minecraft.font;
		NumberFormat format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
		int widest = font.width(objective.getDisplayName());
		int spacer = font.width(": ");
		for (int i = 0; i < lines; i++) {
			PlayerScoreEntry entry = shown.get(i);
			Component name = PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName());
			int scoreWidth = font.width(entry.formatValue(format));
			widest = Math.max(widest, font.width(name) + (scoreWidth > 0 ? spacer + scoreWidth : 0));
		}
		return new int[] {widest, lines};
	}

	private static long sidebarReadAt;
	private static int @Nullable [] sidebarSize;

	/** {@link #sidebar()}, worked out again at most four times a second (it allocates): for drawing it each frame. */
	public static int @Nullable [] sidebarCached() {
		long now = System.nanoTime();
		if (now - sidebarReadAt > 250_000_000L) {
			sidebarReadAt = now;
			sidebarSize = sidebar();
		}
		return sidebarSize;
	}

	/** How many boss bars the server has up (0 outside a world). */
	public static int bossBars() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : ((com.flintclient.client.mixin.feature.BossHealthOverlayAccessor) com.flintclient.client.compat.McCompat.hud().getBossOverlay()).flint$events().size();
	}

	/**
	 * How many of Minecraft's own effect icons show in its top-right rows: {@code {helpful, harmful}}, counting
	 * only effects that show an icon, as {@code Hud.extractEffects} does. Iterating the effects allocates; for
	 * the editor, at most once a second.
	 */
	public static int[] effectIconRows() {
		LocalPlayer player = player();
		int helpful = 0;
		int harmful = 0;
		if (player != null) {
			for (MobEffectInstance effect : player.getActiveEffects()) {
				if (effect.showIcon()) {
					if (effect.getEffect().value().isBeneficial()) {
						helpful++;
					} else {
						harmful++;
					}
				}
			}
		}
		return new int[] {helpful, harmful};
	}
}
