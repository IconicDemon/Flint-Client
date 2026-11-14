package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.CameraMods;
import com.flintclient.client.control.model.mods.LookMods;
import com.flintclient.client.control.model.mods.MapMods;
import com.flintclient.client.control.model.mods.ParityReasons;
import com.flintclient.client.control.model.mods.PlayerMods;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fair play by server: on Hypixel what its rules name as not allowed is off whatever it is set to (and said once), on every
 * server the maps keep to fair play, and in your own worlds everything works as set.
 */
class ServerRulesTest {
	private static int index(String id) {
		return ModuleRegistry.indexOf(id);
	}

	@AfterEach
	void leaveTheWorld() {
		ServerRules.reset();
	}

	@Test
	void hypixelIsKnownByItsAddressOrItsBrand() {
		for (String address : new String[] {"hypixel.net", "mc.hypixel.net", "MC.Hypixel.NET:25565", "hypixel.net.", " stuck.hypixel.net "}) {
			assertEquals("Hypixel", ServerRules.match(address, null).name(), address);
		}
		// Reached through another address, it still says who it is.
		assertEquals("Hypixel", ServerRules.match("209.222.115.1", "Hypixel BungeeCord (2026.10.1)").name());
		assertEquals("Hoplite", ServerRules.match("play.hoplite.gg", null).name());
		for (String other : new String[] {"hypixel.net.example.org", "nothypixel.net", "hypixel.network", "play.example.com", "[::1]:25565", ""}) {
			assertNull(ServerRules.match(other, "Paper"), other);
		}
		assertNull(ServerRules.match(null, null));
	}

	@Test
	void onHypixelItsDisallowedModsAreOff() {
		assertTrue(ServerRules.update(true, false, "mc.hypixel.net", null));
		for (String id : List.of(PlayerMods.Freelook.ID, CameraMods.Camera.ID, MapMods.Minimap.ID, MapMods.WorldMap.ID,
			PlayerMods.HealthIndicators.ID, LookMods.TeamTracker.ID)) {
			assertTrue(ServerRules.blocked(index(id)), id);
			assertEquals("Off on Hypixel: its rules don't allow it", ServerRules.reason(id));
		}
		// What Hypixel allows keeps working: zoom, toggle sprint, Snaplook, Waypoints, Reach Display.
		for (String id : List.of(PlayerMods.Zoom.ID, PlayerMods.ToggleSprint.ID, CameraMods.Snaplook.ID,
			com.flintclient.client.control.model.mods.UtilityMods.Waypoints.ID, com.flintclient.client.control.model.mods.HudMods.ReachDisplay.ID)) {
			assertFalse(ServerRules.blocked(index(id)), id);
			assertNull(ServerRules.reason(id));
		}
		assertFalse(ServerRules.playerHealthShown(), "Damage Indicator shows mobs only there");
		assertTrue(ServerRules.fairPlay());
		assertEquals("Hypixel", ServerRules.serverName());
	}

	@Test
	void ownWorldsAndOtherServersKeepEverything() {
		ServerRules.update(true, true, null, null);
		assertFalse(ServerRules.fairPlay(), "singleplayer: radar and caves");
		assertTrue(ServerRules.playerHealthShown());
		ServerRules.update(true, false, "play.example.com", "Paper");
		assertTrue(ServerRules.fairPlay(), "a server: the maps keep to fair play");
		assertTrue(ServerRules.playerHealthShown());
		assertFalse(ServerRules.blocked(index(PlayerMods.Freelook.ID)));
		assertNull(ServerRules.serverName());
		// Opening your own world to LAN keeps it yours; leaving any world lifts everything.
		ServerRules.update(true, true, "Singleplayer", null);
		assertFalse(ServerRules.fairPlay());
		ServerRules.update(true, false, "hypixel.net", null);
		ServerRules.update(false, false, null, null);
		assertFalse(ServerRules.blocked(index(PlayerMods.Freelook.ID)));
		assertFalse(ServerRules.fairPlay());
		assertFalse(ServerRules.blocked(-1));
		assertFalse(ServerRules.blocked(ModuleRegistry.size()));
	}

	@Test
	void theRulesChangeOnlyWhenWhereYouAreDoes() {
		ServerRules.update(true, false, "hypixel.net", null);
		int revision = ServerRules.revision();
		assertFalse(ServerRules.update(true, false, "hypixel.net", null), "the same place each tick costs nothing");
		assertFalse(ServerRules.update(true, false, "hypixel.net", "Hypixel BungeeCord"), "the brand arriving changes no rule");
		assertEquals(revision, ServerRules.revision());
		assertTrue(ServerRules.update(true, false, "play.example.com", null));
		assertNotEquals(revision, ServerRules.revision());
	}

	@Test
	void theNoteNamesWhatYouHaveOnOnceWhileItStaysOn() {
		ServerRules.update(true, false, "hypixel.net", null);
		int freelook = index(PlayerMods.Freelook.ID);
		int minimap = index(MapMods.Minimap.ID);
		boolean[] on = new boolean[ModuleRegistry.size()];
		on[freelook] = true;
		on[minimap] = true;
		assertEquals("Freelook and Minimap are off on Hypixel: its rules don't allow them", ServerRules.notice(i -> on[i]));
		assertNull(ServerRules.notice(i -> on[i]), "said once");
		on[minimap] = false;
		assertNull(ServerRules.notice(i -> on[i]));
		on[minimap] = true;
		assertEquals("Minimap is off on Hypixel: its rules don't allow it", ServerRules.notice(i -> on[i]), "turned on again: said again");
		// Nothing to say where no rules apply.
		ServerRules.update(true, false, "play.example.com", null);
		assertNull(ServerRules.notice(i -> true));
	}

	@Test
	void sentencesReadWell() {
		assertEquals("A is off on X: its rules don't allow it", ServerRules.sentence(List.of("A"), "X"));
		assertEquals("A and B are off on X: its rules don't allow them", ServerRules.sentence(List.of("A", "B"), "X"));
		assertEquals("A, B and C are off on X: its rules don't allow them", ServerRules.sentence(List.of("A", "B", "C"), "X"));
	}

	@Test
	void theMapsRowsSayFairPlayOnAServer() {
		ModuleState minimap = new ModuleState(ModuleRegistry.get(MapMods.Minimap.ID));
		ModuleState worldMap = new ModuleState(ModuleRegistry.get(MapMods.WorldMap.ID));
		assertNull(ParityReasons.reason(minimap, MapMods.Minimap.RADAR), "your own world: the radar works");
		ServerRules.update(true, false, "play.example.com", null);
		for (var row : List.of(MapMods.Minimap.RADAR, MapMods.Minimap.PLAYERS, MapMods.Minimap.HOSTILE, MapMods.Minimap.CAVE_MODE,
			MapMods.Minimap.LAYER)) {
			assertEquals("Off on servers (fair play)", ParityReasons.reason(minimap, row), row.id());
		}
		assertEquals("Off on servers (fair play)", ParityReasons.reason(worldMap, MapMods.WorldMap.PLAYERS));
		assertEquals("Off on servers (fair play)", ParityReasons.reason(worldMap, MapMods.WorldMap.LAYER));
		assertNull(ParityReasons.reason(minimap, MapMods.Minimap.WAYPOINTS), "waypoints are yours: they stay");
		assertNull(ParityReasons.reason(minimap, MapMods.Minimap.SIZE));
		// Sound Physics' debug rays show where each sound comes from, through walls.
		assertEquals("Off on servers (fair play)", ParityReasons.reason(minimap,
			com.flintclient.client.control.model.mods.SoundPhysicsSettings.RENDER_OCCLUSION));
		ServerRules.update(true, true, null, null);
		assertNull(ParityReasons.reason(minimap, com.flintclient.client.control.model.mods.SoundPhysicsSettings.RENDER_OCCLUSION));
	}

	@Test
	void mobOverlaysOutlineIsOnlyForTheMobYouLookAt() {
		ModuleState mobs = new ModuleState(ModuleRegistry.get(LookMods.MobOverlay.ID));
		assertNull(ParityReasons.reason(mobs, LookMods.MobOverlay.ALWAYS), "Full mode: every mob you can see");
		mobs.set(LookMods.MobOverlay.MODE, 0);
		assertEquals("Outlines show on the mob you look at only", ParityReasons.reason(mobs, LookMods.MobOverlay.ALWAYS));
	}
}
