package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.feature.ZoomifyZoom;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.CameraMods;
import com.flintclient.client.control.model.mods.LookMods;
import com.flintclient.client.control.model.mods.MoreHudMods;
import com.flintclient.client.control.model.mods.MoreMods;
import com.flintclient.client.control.model.mods.PlayerMods;
import com.flintclient.client.control.model.mods.UtilityMods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.google.gson.JsonParser;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * "Shouldn't go above the limit": every number a mod uses stays within its range, set from the menu, a key or a hand-edited
 * file, and the limits Flint keeps for fair play hold (zoom 50×, Team Tracker 64 blocks, Auto Tip every 15 minutes, Reconnect
 * 10 tries at least 3 seconds apart).
 */
class FairLimitsTest {
	/** A layout file someone edited by hand to go past every limit. */
	private static final String EDITED = """
		{"schema": 1, "id": "x", "name": "X", "modules": {
		  "team_tracker": {"enabled": true, "settings": {"distanceLimit": 512, "indicatorSize": 400}},
		  "hypixel": {"enabled": true, "settings": {"autoTipInterval": 1, "autoGGDelay": -5}},
		  "reconnect": {"enabled": true, "settings": {"attempts": 100000, "delay": 0.01, "maxDelay": 0}},
		  "zoom": {"enabled": true, "settings": {"level": 99, "zoomPerStep": 9, "scrollStepCount": 99, "secondaryZoomAmount": 1000}},
		  "fov_changer": {"enabled": true, "settings": {"bow": 999, "speed": -50}},
		  "lighting": {"enabled": true, "settings": {"brightness": 99999}},
		  "motion_blur": {"enabled": true, "settings": {"strength": 500}},
		  "nametags": {"enabled": true, "settings": {"size": 1000, "opacity": 0}},
		  "camera": {"enabled": true, "settings": {"distance": 64, "xOffset": 100, "yOffset": -100, "xRotation": 720}},
		  "hurt_cam": {"enabled": true, "settings": {"multiplier": 50}},
		  "damage_indicator": {"enabled": true, "settings": {"indicatorRange": 999}}
		}}
		""";

	private static ModuleState read(Layout layout, String id) {
		return Objects.requireNonNull(layout.module(id), id);
	}

	@Test
	void aHandEditedFileCantUnlockMore() {
		Layout layout = LayoutCodec.read(JsonParser.parseString(EDITED).getAsJsonObject(), "x");
		ModuleState team = read(layout, LookMods.TeamTracker.ID);
		assertEquals(64, team.integer(LookMods.TeamTracker.DISTANCE), "as far as Minecraft shows a nametag");
		assertEquals(15, team.integer(LookMods.TeamTracker.MARKER_SIZE));
		ModuleState hypixel = read(layout, MoreMods.Hypixel.ID);
		assertEquals(900, hypixel.integer(MoreMods.Hypixel.TIP_INTERVAL), "every 15 minutes at most");
		assertEquals(1, hypixel.integer(MoreMods.Hypixel.GG_DELAY));
		ModuleState reconnect = read(layout, UtilityMods.Reconnect.ID);
		assertEquals(10, reconnect.integer(UtilityMods.Reconnect.ATTEMPTS));
		assertEquals(3F, reconnect.decimal(UtilityMods.Reconnect.DELAY));
		assertEquals(3F, reconnect.decimal(UtilityMods.Reconnect.MAX_DELAY));
		ModuleState zoom = read(layout, PlayerMods.Zoom.ID);
		assertEquals(10, zoom.integer(PlayerMods.Zoom.LEVEL));
		assertEquals(2F, zoom.decimal(PlayerMods.Zoom.PER_STEP));
		assertEquals(20, zoom.integer(PlayerMods.Zoom.SCROLL_STEPS));
		assertEquals(10, zoom.integer(PlayerMods.Zoom.SECONDARY_AMOUNT));
		ModuleState fov = read(layout, VisualMods.FovChanger.ID);
		assertEquals(200, fov.integer(VisualMods.FovChanger.BOW));
		assertEquals(0, fov.integer(VisualMods.FovChanger.SPEED));
		assertEquals(1000, read(layout, VisualMods.Lighting.ID).integer(VisualMods.Lighting.BRIGHTNESS));
		assertEquals(100, read(layout, VisualMods.MotionBlur.ID).integer(VisualMods.MotionBlur.STRENGTH));
		ModuleState tags = read(layout, VisualMods.Nametags.ID);
		assertEquals(200, tags.integer(VisualMods.Nametags.SIZE));
		assertEquals(20, tags.integer(VisualMods.Nametags.OPACITY));
		ModuleState camera = read(layout, CameraMods.Camera.ID);
		assertEquals(16F, camera.decimal(CameraMods.Camera.DISTANCE));
		assertEquals(4F, camera.decimal(CameraMods.Camera.X_OFFSET));
		assertEquals(-4F, camera.decimal(CameraMods.Camera.Y_OFFSET));
		assertEquals(90F, camera.decimal(CameraMods.Camera.X_ROTATION));
		assertEquals(3F, read(layout, PlayerMods.HurtCam.ID).decimal(PlayerMods.HurtCam.MULTIPLIER));
		assertEquals(32, read(layout, MoreHudMods.DamageIndicator.ID).integer(MoreHudMods.DamageIndicator.RANGE));
	}

	@Test
	void keysAndTheMenuStayWithinTheLimitsToo() {
		ModuleState hurt = new ModuleState(ModuleRegistry.get(PlayerMods.HurtCam.ID));
		// HurtCam's + key adds 0.1 each press: it stops at 3.
		for (int i = 0; i < 100; i++) {
			hurt.set(PlayerMods.HurtCam.MULTIPLIER, hurt.decimal(PlayerMods.HurtCam.MULTIPLIER) + 0.1F);
		}
		assertEquals(3F, hurt.decimal(PlayerMods.HurtCam.MULTIPLIER));
		ModuleState team = new ModuleState(ModuleRegistry.get(LookMods.TeamTracker.ID));
		team.set(LookMods.TeamTracker.DISTANCE, 10_000);
		assertEquals(64, team.integer(LookMods.TeamTracker.DISTANCE));
		assertEquals(64, LookMods.TeamTracker.DISTANCE.defaultValue());
	}

	@Test
	void zoomNeverPassesFiftyTimes() {
		assertEquals(50.0, ZoomifyZoom.MAX_DIVISOR);
		assertEquals(50.0, ZoomifyZoom.combined(500, 10), "the main and secondary zooms together");
		assertEquals(40.0, ZoomifyZoom.combined(4, 10));
		assertEquals(1.0, ZoomifyZoom.combined(0.5, 1), "never wider than your own view");
		assertEquals(1.0, ZoomifyZoom.combined(Double.NaN, 4));
		// Zoomify's most: initial 10×, 2× a step, 20 steps scrolled in (10 × 2^20 in Zoomify): 50× in Flint.
		ZoomifyZoom zoom = new ZoomifyZoom(ZoomifyZoom.INSTANT, ZoomifyZoom.INSTANT, () -> 10, () -> 200, () -> 20);
		for (int i = 0; i < 10; i++) {
			zoom.tick(true, 20, 0.05);
		}
		double most = zoom.divisor(1);
		assertTrue(most > 10, "scrolled in past the initial zoom: " + most);
		assertEquals(ZoomifyZoom.MAX_DIVISOR, most, 1e-9);
	}
}
