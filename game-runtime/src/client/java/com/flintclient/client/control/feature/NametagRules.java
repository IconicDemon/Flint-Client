package com.flintclient.client.control.feature;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Whether your own nametag shows over you in third person (the Nametags mod's "Nametag in perspective"), decided once per
 * entity per frame by Minecraft's own nametag rules. Minecraft never draws the camera entity's nametag, so this is the only
 * nametag Flint ever adds; every other one is Minecraft's decision, which Flint only decorates (the Flint mark, ping, Tier
 * Tagger's tier) or hides (the Nametags mod's keys), never doubles.
 *
 * <p>Your own nametag follows the rules Minecraft applies to another player's
 * ({@code LivingEntityRenderer.shouldShowName}, the same from 1.8.9 to 26.3), with you as both the player and the viewer:
 * <ul>
 * <li>On a team, the team's nametag visibility as Minecraft reads it for a teammate: {@code always} shows it (unless you
 * are invisible to yourself), {@code never} and {@code hideForOwnTeam} hide it, {@code hideForOtherTeams} shows it. A
 * server that draws names itself (the TAB plugin's nametags, a text display or armour stand above you) sets
 * {@code never}, so its name is the only one.</li>
 * <li>Off a team: shown while visible and nothing rides you.</li>
 * </ul>
 * Flint is stricter than Minecraft where Minecraft would otherwise draw two labels or a nametag a screenshot shouldn't
 * have: nothing riding you (a passenger is a server's hologram or a player whose own nametag shows), the GUI shown (F1
 * hides it), and only over you in the world (never over your picture in the inventory or a HUD). Sneaking keeps
 * Minecraft's own handling (no see-through part, hidden past 32 blocks). Pure Java: every line reads the inputs from its
 * own Minecraft.
 */
public final class NametagRules {
	/** A team's nametag visibility ({@code Team.Visibility}), by Minecraft's constant names. */
	public enum TeamRule {
		ALWAYS,
		NEVER,
		HIDE_FOR_OTHER_TEAMS,
		HIDE_FOR_OWN_TEAM,
		/** A rule this Flint doesn't know (a later Minecraft's): never shown over you. */
		OTHER;

		/** The rule named {@code name} (Minecraft's enum constant), {@link #OTHER} for one this Flint doesn't know. */
		public static TeamRule of(String name) {
			try {
				return valueOf(name.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return OTHER;
			}
		}
	}

	private NametagRules() {
	}

	/**
	 * Minecraft's answer for a player's nametag when the player is on a team with {@code rule} ({@code shouldShowName}'s
	 * team branch): {@code viewerTeam} whether the viewer is on a team at all, {@code allied} whether that team is the
	 * player's (or allied to it), {@code seeFriendlyInvisibles} the player's team's "see invisible teammates" option,
	 * {@code visible} whether the player is visible to the viewer.
	 */
	public static boolean teamShows(TeamRule rule, boolean viewerTeam, boolean allied, boolean seeFriendlyInvisibles, boolean visible) {
		return switch (rule) {
			case ALWAYS -> visible;
			case NEVER, OTHER -> false;
			case HIDE_FOR_OTHER_TEAMS -> !viewerTeam ? visible : allied && (seeFriendlyInvisibles || visible);
			case HIDE_FOR_OWN_TEAM -> !viewerTeam ? visible : !allied && visible;
		};
	}

	/**
	 * Whether your own nametag shows over you this frame.
	 *
	 * @param option the Nametags mod is on with "Nametag in perspective"
	 * @param thirdPerson the camera is behind or in front of you (F5)
	 * @param hudHidden the GUI is hidden (F1)
	 * @param inWorld the nametag is for you in the world (not your picture in the inventory or a HUD)
	 * @param tooFar Minecraft's distance rule hides it (sneaking, 32 blocks or more from the camera)
	 * @param visible you are visible to yourself (Minecraft's {@code isInvisibleTo}: invisibility unless your team sees
	 *                invisible teammates; a spectator sees everyone)
	 * @param ridden something rides you (a server's hologram, or another player)
	 * @param team your team's nametag rule, or null off a team
	 * @param seeFriendlyInvisibles your team's "see invisible teammates" option
	 */
	public static boolean ownShown(boolean option, boolean thirdPerson, boolean hudHidden, boolean inWorld, boolean tooFar, boolean visible,
		boolean ridden, @Nullable TeamRule team, boolean seeFriendlyInvisibles) {
		if (!option || !thirdPerson || hudHidden || !inWorld || tooFar || ridden) {
			return false;
		}
		// You are your own teammate: the viewer's team is the player's.
		return team == null ? visible : teamShows(team, true, true, seeFriendlyInvisibles, visible);
	}

	/**
	 * A nametag's final answer: Minecraft's ({@code vanilla}), else yours over you in third person when
	 * {@link #ownShown} allows it ({@code own}: the entity is you and Minecraft hid it only because the camera is yours).
	 * Another entity's nametag is always Minecraft's answer: Flint never shows one Minecraft hid.
	 */
	public static boolean shown(boolean vanilla, boolean own, boolean ownShown) {
		return vanilla || own && ownShown;
	}
}
