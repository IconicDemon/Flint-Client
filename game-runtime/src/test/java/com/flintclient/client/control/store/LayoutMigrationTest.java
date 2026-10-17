package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.CpsButtons;
import com.flintclient.client.control.model.DirectionFormat;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.KeystrokesLayout;
import com.flintclient.client.control.model.LabelPosition;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A layout file written by the previous Flint reads into the new settings, looks the same, and once saved holds the
 * new keys and none of the retired ones. Also the new shared accent colour in files.
 */
class LayoutMigrationTest {
	/** The previous Flint's own output for a few changed modules (every setting written, as it always did). */
	private static final String OLD_FILE = """
		{"schema": 1, "id": "old", "name": "Old",
		 "appearance": {"theme": "flint", "textColor": "default", "padding": 3},
		 "modules": {
		   "fps": {"enabled": true, "settings": {"showLow": true},
		           "hud": {"anchor": "TOP_LEFT", "x": 4.0, "y": 4.0, "scale": 1.0, "appearance": {"showLabel": false}}},
		   "ping": {"enabled": true, "settings": {"showBars": true},
		            "hud": {"anchor": "TOP_LEFT", "x": 4.0, "y": 19.0, "scale": 1.0}},
		   "cps": {"enabled": true, "settings": {"showRight": false},
		           "hud": {"anchor": "TOP_LEFT", "x": 4.0, "y": 34.0, "scale": 1.0}},
		   "keystrokes": {"enabled": true, "settings": {"showMouse": true, "showSpace": false, "showCps": false, "keySize": 18},
		                  "hud": {"anchor": "BOTTOM_LEFT", "x": 4.0, "y": 40.0, "scale": 1.0}},
		   "coordinates": {"enabled": true, "settings": {"decimals": "one", "layout": "horizontal", "otherDimension": false},
		                   "hud": {"anchor": "TOP_LEFT", "x": 4.0, "y": 49.0, "scale": 1.0, "appearance": {"showLabel": false}}},
		   "direction": {"enabled": true, "settings": {"showDegrees": true, "showAxis": false},
		                 "hud": {"anchor": "TOP_LEFT", "x": 4.0, "y": 84.0, "scale": 1.0}}}}
		""";

	@Test
	void anOldFileLooksTheSame() {
		Layout layout = LayoutCodec.read(JsonParser.parseString(OLD_FILE).getAsJsonObject(), "old");
		ModuleState fps = layout.module(ModuleId.FPS);
		assertFalse(fps.bool(ModuleSettings.Fps.SHOW_LABEL), "the old style switch hid the label");
		assertTrue(fps.bool(ModuleSettings.Fps.SHOW_LOW));
		assertTrue(fps.appearance().isInherit());
		assertTrue(layout.module(ModuleId.PING).bool(ModuleSettings.Ping.SHOW_BARS), "a kept setting keeps its value, not the new default");
		assertEquals(LabelPosition.AFTER, layout.module(ModuleId.PING).choice(ModuleSettings.Ping.LABEL_POSITION));
		assertEquals(CpsButtons.LEFT, layout.module(ModuleId.CPS).choice(ModuleSettings.Cps.BUTTONS));
		ModuleState keys = layout.module(ModuleId.KEYSTROKES);
		assertEquals(KeystrokesLayout.KEYS_MOUSE, keys.choice(ModuleSettings.Keystrokes.LAYOUT));
		assertEquals(18, keys.integer(ModuleSettings.Keystrokes.KEY_SIZE));
		assertFalse(layout.module(ModuleId.COORDINATES).bool(ModuleSettings.Coordinates.LABELS));
		assertEquals(DirectionFormat.DETAILED, layout.module(ModuleId.DIRECTION).choice(ModuleSettings.Direction.FORMAT));
		assertFalse(layout.module(ModuleId.DIRECTION).bool(ModuleSettings.Direction.SHOW_AXIS));
	}

	@Test
	void savingWritesTheNewKeysAndNeverTheOldOnes() {
		JsonObject root = JsonParser.parseString(OLD_FILE).getAsJsonObject();
		Layout layout = LayoutCodec.read(root, "old");
		LayoutCodec.write(layout, root);
		JsonObject modules = root.getAsJsonObject("modules");
		JsonObject cps = settings(modules, "cps");
		assertFalse(cps.has("showRight"));
		assertEquals("left", cps.get("buttons").getAsString());
		JsonObject keys = settings(modules, "keystrokes");
		assertFalse(keys.has("showMouse"));
		assertFalse(keys.has("showSpace"));
		assertEquals("keys_mouse", keys.get("layout").getAsString());
		assertFalse(settings(modules, "fps").get("showLabel").getAsBoolean(), "FPS writes its own label switch");
		assertFalse(settings(modules, "fps").has("labelPosition"), "and never its older label position again");
		assertFalse(modules.getAsJsonObject("fps").getAsJsonObject("hud").has("appearance"), "the dropped Show label leaves the file");
		assertFalse(settings(modules, "coordinates").get("labels").getAsBoolean());
		assertFalse(modules.getAsJsonObject("coordinates").getAsJsonObject("hud").has("appearance"));
		JsonObject direction = settings(modules, "direction");
		assertEquals("detailed", direction.get("format").getAsString());
		assertFalse(direction.get("showAxis").getAsBoolean(), "a kept key stays");
		assertEquals(layout, LayoutCodec.read(root, "old"), "and reads back the same, with nothing left to migrate");
	}

	@Test
	void aMigratedFileIsNotDirtyUntilSomethingChanges(@TempDir Path config) throws Exception {
		Path layouts = config.resolve("flintclient").resolve("layouts");
		Files.createDirectories(layouts);
		Files.writeString(layouts.resolve("old.json"), OLD_FILE);
		ControlSettings settings = new ControlSettings(config);
		settings.load();
		LayoutStore store = new LayoutStore(config, settings);
		store.load();
		assertFalse(store.dirty("old"), "reading migrates in memory; the file is rewritten only with a real change");
		Layout old = store.get("old");
		old.module(ModuleId.PING).set(ModuleSettings.Ping.SHOW_BARS, false);
		assertTrue(store.save());
		String saved = Files.readString(layouts.resolve("old.json"));
		assertFalse(saved.contains("showRight"));
		assertFalse(saved.contains("showMouse"));
		assertTrue(saved.contains("\"buttons\""));
	}

	@Test
	void theAccentColorIsWrittenOnlyOnceChosen() {
		Layout layout = new Layout("a", "A");
		JsonObject json = LayoutCodec.toJson(layout);
		assertFalse(json.getAsJsonObject("appearance").has("accentColor"), "follows the style until chosen");
		assertNull(LayoutCodec.read(json, "a").appearance().accentColor());
		layout.setAppearance(layout.appearance().withAccentColor(HudColor.SKY));
		ModuleState fps = layout.module(ModuleId.FPS);
		fps.setAppearance(Appearance.INHERIT.withAccentColor(HudColor.MINT));
		JsonObject written = LayoutCodec.toJson(layout);
		assertEquals("sky", written.getAsJsonObject("appearance").get("accentColor").getAsString());
		assertEquals("mint", written.getAsJsonObject("modules").getAsJsonObject("fps").getAsJsonObject("hud").getAsJsonObject("appearance")
			.get("accentColor").getAsString());
		Layout read = LayoutCodec.read(written, "a");
		assertEquals(layout, read);
		assertEquals(HudColor.MINT, read.effectiveAppearance(read.module(ModuleId.FPS)).accentColor());
		assertEquals(HudColor.SKY, read.effectiveAppearance(read.module(ModuleId.PING)).accentColor());
		JsonObject bad = JsonParser.parseString("{\"appearance\": {\"accentColor\": \"neon\"}}").getAsJsonObject();
		assertNull(LayoutCodec.read(bad, "b").appearance().accentColor(), "an unknown colour follows the style");
	}

	private static JsonObject settings(JsonObject modules, String id) {
		return modules.getAsJsonObject(id).getAsJsonObject("settings");
	}
}
