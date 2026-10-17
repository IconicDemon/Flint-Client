package com.flintclient.client.control.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
import net.minecraft.world.scores.TeamColor;
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
			Optional<TeamColor> color = team.getColor();
			if (color.isPresent()) {
				objective = scoreboard.getDisplayObjective(color.get().displaySlot());
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
