package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.NametagRules.TeamRule;
import org.junit.jupiter.api.Test;

class NametagRulesTest {
	/** Your own nametag with everything allowing it but {@code team}. */
	private static boolean own(boolean option, TeamRule team) {
		return NametagRules.ownShown(option, true, false, true, false, true, false, team, false);
	}

	@Test
	void yourOwnNametagFollowsYourTeamsRuleAsMinecraftAppliesItToATeammate() {
		// Off a team: shown in third person while the option is on.
		assertTrue(own(true, null));
		assertTrue(own(true, TeamRule.ALWAYS));
		assertTrue(own(true, TeamRule.HIDE_FOR_OTHER_TEAMS), "you are on your own team");
		// A server that draws its own name above you (TAB, a text display, an armour stand) hides Minecraft's: never two.
		assertFalse(own(true, TeamRule.NEVER));
		assertFalse(own(true, TeamRule.HIDE_FOR_OWN_TEAM), "you are on your own team");
		assertFalse(own(true, TeamRule.OTHER), "a rule this Flint doesn't know never shows one");
		// The option off: never, whatever the team.
		for (TeamRule rule : TeamRule.values()) {
			assertFalse(own(false, rule), rule.name());
		}
		assertFalse(own(false, null));
	}

	@Test
	void anotherPlayersNametagIsAlwaysMinecraftsAnswer() {
		for (boolean vanilla : new boolean[] {true, false}) {
			for (boolean ownShown : new boolean[] {true, false}) {
				assertEquals(vanilla, NametagRules.shown(vanilla, false, ownShown), "Flint never shows one Minecraft hid, nor hides one here");
			}
		}
		assertTrue(NametagRules.shown(false, true, true), "yours in third person");
		assertFalse(NametagRules.shown(false, true, false));
		assertTrue(NametagRules.shown(true, true, false), "Minecraft already shows yours (the camera is another entity's)");
	}

	@Test
	void minecraftsTeamRulesForAnotherPlayer() {
		// Viewer off a team: every rule but never shows a visible player.
		for (TeamRule rule : new TeamRule[] {TeamRule.ALWAYS, TeamRule.HIDE_FOR_OTHER_TEAMS, TeamRule.HIDE_FOR_OWN_TEAM}) {
			assertTrue(NametagRules.teamShows(rule, false, false, false, true), rule.name());
			assertFalse(NametagRules.teamShows(rule, false, false, false, false), rule.name() + ": invisible");
		}
		assertFalse(NametagRules.teamShows(TeamRule.NEVER, false, false, false, true));
		// Viewer on the same team.
		assertTrue(NametagRules.teamShows(TeamRule.HIDE_FOR_OTHER_TEAMS, true, true, false, true));
		assertTrue(NametagRules.teamShows(TeamRule.HIDE_FOR_OTHER_TEAMS, true, true, true, false), "an invisible teammate the team can see");
		assertFalse(NametagRules.teamShows(TeamRule.HIDE_FOR_OTHER_TEAMS, true, true, false, false));
		assertFalse(NametagRules.teamShows(TeamRule.HIDE_FOR_OWN_TEAM, true, true, false, true));
		// Viewer on another team.
		assertFalse(NametagRules.teamShows(TeamRule.HIDE_FOR_OTHER_TEAMS, true, false, false, true));
		assertTrue(NametagRules.teamShows(TeamRule.HIDE_FOR_OWN_TEAM, true, false, false, true));
		assertFalse(NametagRules.teamShows(TeamRule.HIDE_FOR_OWN_TEAM, true, false, false, false));
		assertTrue(NametagRules.teamShows(TeamRule.ALWAYS, true, false, false, true));
		assertFalse(NametagRules.teamShows(TeamRule.NEVER, true, true, true, true));
	}

	@Test
	void yourOwnNametagNeedsThirdPersonTheGuiYouInTheWorldAndNothingRidingYou() {
		assertTrue(NametagRules.ownShown(true, true, false, true, false, true, false, null, false));
		assertFalse(NametagRules.ownShown(true, false, false, true, false, true, false, null, false), "first person");
		assertFalse(NametagRules.ownShown(true, true, true, true, false, true, false, null, false), "F1");
		assertFalse(NametagRules.ownShown(true, true, false, false, false, true, false, null, false), "your picture in the inventory");
		assertFalse(NametagRules.ownShown(true, true, false, true, true, true, false, null, false), "sneaking, far from the camera");
		assertFalse(NametagRules.ownShown(true, true, false, true, false, true, true, null, false), "a hologram riding you");
		assertFalse(NametagRules.ownShown(true, true, false, true, false, true, true, TeamRule.ALWAYS, false), "riding you, even on a team");
		assertFalse(NametagRules.ownShown(true, true, true, true, false, true, false, TeamRule.ALWAYS, false), "F1 hides it on a team too");
	}

	@Test
	void invisibleYouShowsNoNametagUnlessYourTeamSeesInvisibleTeammates() {
		assertFalse(NametagRules.ownShown(true, true, false, true, false, false, false, null, false));
		assertFalse(NametagRules.ownShown(true, true, false, true, false, false, false, TeamRule.ALWAYS, false));
		assertTrue(NametagRules.ownShown(true, true, false, true, false, false, false, TeamRule.HIDE_FOR_OTHER_TEAMS, true),
			"a team that sees invisible teammates sees you (Minecraft's HIDE_FOR_OTHER_TEAMS rule)");
		assertFalse(NametagRules.ownShown(true, true, false, true, false, false, false, TeamRule.HIDE_FOR_OTHER_TEAMS, false));
	}

	@Test
	void teamRulesAreReadByMinecraftsConstantNames() {
		assertEquals(TeamRule.ALWAYS, TeamRule.of("ALWAYS"));
		assertEquals(TeamRule.NEVER, TeamRule.of("NEVER"));
		assertEquals(TeamRule.HIDE_FOR_OTHER_TEAMS, TeamRule.of("HIDE_FOR_OTHER_TEAMS"));
		assertEquals(TeamRule.HIDE_FOR_OWN_TEAM, TeamRule.of("hide_for_own_team"));
		assertEquals(TeamRule.OTHER, TeamRule.of("HIDE_FOR_SPECTATORS"), "a later Minecraft's rule");
	}
}
