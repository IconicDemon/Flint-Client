package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.FpsDisplayMode;
import com.flintclient.client.control.model.FpsSizing;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.TextAlign;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FPS's settings, position and on/off state survive a save and a restart, per layout (profile), and bad values recover one by one. */
class FpsPersistenceTest {
	@TempDir
	Path config;

	private LayoutStore store() {
		return new LayoutStore(config, new ControlSettings(config, JsonStoreTest.CLOCK), JsonStoreTest.CLOCK);
	}

	private static void customise(ModuleState fps) {
		fps.setEnabled(true);
		fps.set(ModuleSettings.Fps.DISPLAY_MODE, FpsDisplayMode.BRACKETS);
		fps.set(ModuleSettings.Fps.BACKGROUND, false);
		fps.set(ModuleSettings.Fps.BACKGROUND_TYPE, FpsSizing.STATIC);
		fps.set(ModuleSettings.Fps.WIDTH, 90);
		fps.set(ModuleSettings.Fps.HEIGHT, 22);
		fps.set(ModuleSettings.Fps.BORDER, true);
		fps.set(ModuleSettings.Fps.BORDER_COLOR, 0x123456);
		fps.set(ModuleSettings.Fps.BORDER_THICKNESS, 3);
		fps.set(ModuleSettings.Fps.CORNER_RADIUS, 6);
		fps.set(ModuleSettings.Fps.TEXT_COLOR, 0xFF8139);
		fps.set(ModuleSettings.Fps.TEXT_SHADOW, false);
		fps.set(ModuleSettings.Fps.REVERSED, true);
		fps.set(ModuleSettings.Fps.ALIGN, TextAlign.RIGHT);
		fps.set(ModuleSettings.Fps.BACKGROUND_COLOR, 0x202020);
		fps.set(ModuleSettings.Fps.BACKGROUND_OPACITY, 70);
		fps.setPlacement(HudPlacement.of(Anchor.BOTTOM_RIGHT, 12, 34).withScale(1.5F));
	}

	@Test
	void everySettingRoundTripsThroughTheFile() {
		Layout layout = new Layout("pvp", "PvP");
		customise(layout.module(ModuleId.FPS));
		JsonObject json = LayoutCodec.toJson(layout);
		Layout back = LayoutCodec.read(json, "pvp");
		assertEquals(layout, back);
		JsonObject settings = json.getAsJsonObject("modules").getAsJsonObject("fps").getAsJsonObject("settings");
		assertEquals("#FF8139", settings.get("textColor").getAsString(), "colours are readable hex in the file");
		assertEquals("brackets", settings.get("displayMode").getAsString());
	}

	@Test
	void oneBadValueNeverCostsTheOthers() {
		String file = """
			{"schema": 1, "id": "x", "name": "X", "modules": {"fps": {"enabled": true, "settings": {
			  "displayMode": "sparkles", "textColor": "#ZZZZZZ", "width": 99999, "height": "tall", "reversed": "yes",
			  "border": true, "borderColor": "#00ff00", "backgroundOpacity": 30},
			  "hud": {"anchor": "TOP_LEFT", "x": 8.0, "y": 9.0, "scale": 1.25}}}}
			""";
		Layout layout = LayoutCodec.read(JsonParser.parseString(file).getAsJsonObject(), "x");
		ModuleState fps = layout.module(ModuleId.FPS);
		assertEquals(FpsDisplayMode.BACKGROUND, fps.choice(ModuleSettings.Fps.DISPLAY_MODE), "unknown mode: the default");
		assertEquals(0xFFFFFF, fps.rgb(ModuleSettings.Fps.TEXT_COLOR), "malformed colour: the default");
		assertEquals(160, fps.integer(ModuleSettings.Fps.WIDTH), "out of range: clamped");
		assertEquals(13, fps.integer(ModuleSettings.Fps.HEIGHT), "not a number: the default");
		assertFalse(fps.bool(ModuleSettings.Fps.REVERSED), "not a boolean: the default");
		assertTrue(fps.bool(ModuleSettings.Fps.BORDER), "good values around them are kept");
		assertEquals(0x00FF00, fps.rgb(ModuleSettings.Fps.BORDER_COLOR));
		assertEquals(30, fps.integer(ModuleSettings.Fps.BACKGROUND_OPACITY));
		assertTrue(fps.enabled());
		assertEquals(1.25F, fps.placement().scale());
		assertEquals(8.0F, fps.placement().x());
	}

	@Test
	void eachProfileKeepsItsOwnFpsAcrossARestart() {
		LayoutStore store = store();
		store.load();
		customise(store.active().module(ModuleId.FPS));
		String other = store.create("Minimal", null, null).id();
		store.switchTo(other);
		ModuleState plain = store.active().module(ModuleId.FPS);
		plain.setEnabled(false);
		plain.set(ModuleSettings.Fps.TEXT_COLOR, 0x00FFFF);
		assertTrue(store.save());

		LayoutStore restarted = store();
		restarted.load();
		assertEquals(other, restarted.activeId(), "the profile in use is remembered");
		ModuleState now = restarted.active().module(ModuleId.FPS);
		assertFalse(now.enabled(), "this profile has FPS off");
		assertEquals(0x00FFFF, now.rgb(ModuleSettings.Fps.TEXT_COLOR));
		assertEquals(FpsDisplayMode.BRACKETS, now.choice(ModuleSettings.Fps.DISPLAY_MODE), "a new profile started as a copy of the one in use");

		String first = restarted.list().getFirst().id().equals(other) ? restarted.list().get(1).id() : restarted.list().getFirst().id();
		restarted.switchTo(first);
		ModuleState back = restarted.active().module(ModuleId.FPS);
		assertTrue(back.enabled(), "the first profile still has FPS on");
		assertEquals(FpsDisplayMode.BRACKETS, back.choice(ModuleSettings.Fps.DISPLAY_MODE));
		assertEquals(0xFF8139, back.rgb(ModuleSettings.Fps.TEXT_COLOR));
		assertEquals(HudPlacement.of(Anchor.BOTTOM_RIGHT, 12, 34).withScale(1.5F), back.placement(), "and its position");
	}
}
