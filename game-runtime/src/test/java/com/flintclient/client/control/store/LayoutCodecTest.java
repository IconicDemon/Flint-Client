package com.flintclient.client.control.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.CoordinatesLayout;
import com.flintclient.client.control.model.Decimals;
import com.flintclient.client.control.model.DurabilityFormat;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.KeystrokesLayout;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleSettings;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.Orientation;
import com.flintclient.client.control.model.Preset;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutCodecTest {
	private static Layout busyLayout() {
		Layout layout = Preset.PVP.create("ranked", "Ranked · Night", HudThemeId.CLASSIC);
		layout.setAppearance(new Appearance(HudThemeId.CLASSIC, HudColor.SAND, false, 45, 6, null));
		ModuleState coordinates = layout.module(ModuleId.COORDINATES);
		coordinates.setEnabled(true);
		coordinates.set(ModuleSettings.Coordinates.DECIMALS, Decimals.ONE);
		coordinates.set(ModuleSettings.Coordinates.LAYOUT, CoordinatesLayout.HORIZONTAL);
		coordinates.setPlacement(new HudPlacement(Anchor.BOTTOM, -12.5F, 30.25F, 1.25F));
		coordinates.setAppearance(new Appearance(HudThemeId.MINIMAL, HudColor.LAVENDER, true, 10, 0, false));
		ModuleState armor = layout.module(ModuleId.ARMOR);
		armor.set(ModuleSettings.Armor.DURABILITY, DurabilityFormat.NUMBER);
		armor.set(ModuleSettings.Armor.SHOW_HANDS, true);
		armor.setAppearance(Appearance.INHERIT.withTextColor(HudColor.EMBER));
		layout.module(ModuleId.KEYSTROKES).set(ModuleSettings.Keystrokes.KEY_SIZE, 21);
		layout.module(ModuleId.FPS).setPlacement(new HudPlacement(Anchor.TOP_LEFT, 0.1F + 0.2F, 1F / 3F, 0.75F));
		return layout;
	}

	@Test
	void roundTripsExactly() {
		Layout layout = busyLayout();
		JsonObject json = LayoutCodec.toJson(layout);
		Layout read = LayoutCodec.read(json, "ranked");
		assertEquals(layout, read);
		String text = new GsonBuilder().setPrettyPrinting().create().toJson(json);
		assertEquals(layout, LayoutCodec.read(JsonParser.parseString(text).getAsJsonObject(), "ranked"), "through text too");
		Layout empty = new Layout("default", "Default");
		assertEquals(empty, LayoutCodec.read(LayoutCodec.toJson(empty), "default"));
	}

	@Test
	void theFileHasTheDocumentedShape() {
		JsonObject json = LayoutCodec.toJson(busyLayout());
		assertEquals(List.of("schema", "id", "name", "appearance", "modules"), List.copyOf(json.keySet()));
		assertEquals(1, json.get("schema").getAsInt());
		assertEquals("ranked", json.get("id").getAsString());
		assertEquals(JsonParser.parseString("{\"theme\":\"classic\",\"textColor\":\"sand\",\"background\":false,\"backgroundOpacity\":45,"
			+ "\"padding\":6}"), json.get("appearance"));
		JsonObject modules = json.getAsJsonObject("modules");
		assertEquals(ModuleId.ALL, List.copyOf(modules.keySet()), "every module, on or off");
		JsonObject coordinates = modules.getAsJsonObject("coordinates");
		assertEquals(JsonParser.parseString("""
			{"enabled": true,
			 "settings": {"layout": "horizontal", "decimals": "one", "labels": true, "showDirection": false, "otherDimension": false},
			 "hud": {"anchor": "BOTTOM", "x": -12.5, "y": 30.25, "scale": 1.25,
			         "appearance": {"theme": "minimal", "textColor": "lavender", "background": true, "backgroundOpacity": 10,
			                        "padding": 0, "showLabel": false}}}
			"""), coordinates);
		JsonObject fps = modules.getAsJsonObject("fps");
		assertFalse(fps.getAsJsonObject("hud").has("appearance"), "no overrides, no appearance object");
		assertEquals(JsonParser.parseString("{\"textColor\": \"ember\"}"),
			modules.getAsJsonObject("armor").getAsJsonObject("hud").get("appearance"), "only what is overridden");
	}

	@Test
	void aNewLayoutLeavesThePanelToItsStyle() {
		JsonObject json = LayoutCodec.toJson(new Layout("plain", "Plain"));
		assertEquals(JsonParser.parseString("{\"theme\": \"flint\", \"textColor\": \"default\", \"padding\": 3}"), json.get("appearance"),
			"no background or opacity until one is chosen");
		Layout classic = LayoutCodec.read(LayoutCodec.toJson(Preset.BALANCED.create("b", "B", HudThemeId.CLASSIC)), "b");
		ModuleState fps = classic.module(ModuleId.FPS);
		assertEquals(40, classic.effectiveAppearance(fps).backgroundOpacity(), "Classic's own lighter panel");
		assertEquals(true, classic.effectiveAppearance(fps).background());
		Layout minimal = LayoutCodec.read(LayoutCodec.toJson(Preset.MINIMAL.create("m", "M", HudThemeId.MINIMAL)), "m");
		assertEquals(false, minimal.effectiveAppearance(minimal.module(ModuleId.FPS)).background(), "Minimal has no panel");
	}

	@Test
	void valuesThisVersionCantUseSurviveUntilChanged() {
		JsonObject json = JsonParser.parseString("""
			{"schema": 1, "id": "x", "name": "X",
			 "appearance": {"theme": "glass", "textColor": "default", "backgroundOpacity": 72, "padding": 3},
			 "modules": {
			   "armor": {"enabled": true, "settings": {"durability": "bars", "layout": "VERTICAL"},
			             "hud": {"anchor": "right", "x": 4, "y": 0, "scale": 1, "appearance": {"theme": "glass", "padding": 99}}}}}
			""").getAsJsonObject();
		Layout layout = LayoutCodec.read(json, "x");
		assertEquals(HudThemeId.FLINT, layout.appearance().theme(), "an unknown style reads as the default");
		assertEquals(70, layout.appearance().backgroundOpacity(), "snapped to the 5 % steps");
		ModuleState armor = layout.module(ModuleId.ARMOR);
		assertEquals(DurabilityFormat.PERCENT, armor.choice(ModuleSettings.Armor.DURABILITY));
		assertNull(armor.appearance().theme(), "an unknown override follows the layout");
		assertEquals(8, armor.appearance().padding(), "clamped");
		layout.module(ModuleId.CPS).setEnabled(true);
		LayoutCodec.write(layout, json);
		JsonObject armorJson = json.getAsJsonObject("modules").getAsJsonObject("armor");
		assertEquals("glass", json.getAsJsonObject("appearance").get("theme").getAsString(), "kept for the Flint that wrote it");
		assertEquals(72, json.getAsJsonObject("appearance").get("backgroundOpacity").getAsInt());
		assertEquals("bars", armorJson.getAsJsonObject("settings").get("durability").getAsString());
		assertEquals("VERTICAL", armorJson.getAsJsonObject("settings").get("layout").getAsString(), "its own spelling stays");
		assertEquals(JsonParser.parseString("{\"anchor\": \"right\", \"x\": 4, \"y\": 0, \"scale\": 1, \"appearance\": {\"theme\": \"glass\", "
			+ "\"padding\": 99}}"), armorJson.get("hud"));
		assertTrue(json.getAsJsonObject("modules").getAsJsonObject("cps").get("enabled").getAsBoolean(), "real changes are written");

		layout.setAppearance(layout.appearance().withTheme(HudThemeId.MINIMAL).withBackgroundOpacity(60));
		armor.set(ModuleSettings.Armor.DURABILITY, DurabilityFormat.NUMBER);
		armor.setAppearance(Appearance.INHERIT);
		armor.setPlacement(armor.placement().withOffset(10, 0));
		LayoutCodec.write(layout, json);
		armorJson = json.getAsJsonObject("modules").getAsJsonObject("armor");
		assertEquals("minimal", json.getAsJsonObject("appearance").get("theme").getAsString(), "replaced once changed");
		assertEquals(60, json.getAsJsonObject("appearance").get("backgroundOpacity").getAsInt());
		assertEquals("number", armorJson.getAsJsonObject("settings").get("durability").getAsString());
		assertEquals(JsonParser.parseString("{\"theme\": \"glass\"}"), armorJson.getAsJsonObject("hud").get("appearance"),
			"a cleared override goes; one this version can't show (or clear) stays");
		assertEquals(10.0, armorJson.getAsJsonObject("hud").get("x").getAsDouble());
		assertEquals(layout, LayoutCodec.read(json, "x"));
	}

	@Test
	void unknownKeysEverywhereSurviveAWrite() {
		JsonObject json = JsonParser.parseString("""
			{"schema": 1, "id": "x", "name": "X", "exportedBy": "Flint 9",
			 "appearance": {"theme": "flint", "font": "serif"},
			 "modules": {
			   "minimap": {"enabled": true, "zoom": 3},
			   "fps": {"enabled": true, "pinned": true,
			           "settings": {"showLow": true, "graph": "bars"},
			           "hud": {"anchor": "TOP_LEFT", "x": 4, "y": 4, "scale": 1, "rotation": 90,
			                   "appearance": {"textColor": "sky", "glow": false}}}}}
			""").getAsJsonObject();
		Layout layout = LayoutCodec.read(json, "x");
		layout.module(ModuleId.FPS).set(ModuleSettings.Fps.SHOW_LOW, false);
		layout.module(ModuleId.FPS).setAppearance(Appearance.INHERIT);
		LayoutCodec.write(layout, json);
		assertEquals("Flint 9", json.get("exportedBy").getAsString());
		assertEquals("serif", json.getAsJsonObject("appearance").get("font").getAsString());
		JsonObject modules = json.getAsJsonObject("modules");
		assertEquals(JsonParser.parseString("{\"enabled\": true, \"zoom\": 3}"), modules.get("minimap"), "a module from a newer Flint is kept");
		JsonObject fps = modules.getAsJsonObject("fps");
		assertTrue(fps.get("pinned").getAsBoolean());
		assertEquals("bars", fps.getAsJsonObject("settings").get("graph").getAsString());
		assertFalse(fps.getAsJsonObject("settings").get("showLow").getAsBoolean());
		assertEquals(90, fps.getAsJsonObject("hud").get("rotation").getAsInt());
		assertEquals(JsonParser.parseString("{\"glow\": false}"), fps.getAsJsonObject("hud").get("appearance"),
			"cleared overrides go, unknown ones stay");
	}

	@Test
	void eachBadFieldFallsBackOnItsOwn() {
		JsonObject json = JsonParser.parseString("""
			{"schema": 1, "id": "elsewhere", "name": 42,
			 "appearance": {"theme": "neon", "textColor": "ember", "background": "no", "backgroundOpacity": 300, "padding": 2.6},
			 "modules": {
			   "fps": {"enabled": "true", "settings": {"showLow": 1}},
			   "keystrokes": {"enabled": true, "settings": {"keySize": 99, "showSpace": false},
			                  "hud": {"anchor": "MIDDLE", "x": "4", "y": 12, "scale": 3}},
			   "armor": {"enabled": true, "settings": {"durability": "bars", "layout": "HORIZONTAL"},
			             "hud": {"appearance": {"theme": "classic", "textColor": "red", "padding": 99, "showLabel": "no"}}},
			   "effects": "on",
			   "ping": {"settings": [], "hud": []}}}
			""").getAsJsonObject();
		Layout layout = LayoutCodec.read(json, "my-file");
		assertEquals("my-file", layout.id(), "the file name wins");
		assertEquals("my-file", layout.name(), "no usable name: the id");
		Appearance shared = layout.appearance();
		assertEquals(HudThemeId.FLINT, shared.theme());
		assertEquals(HudColor.EMBER, shared.textColor());
		assertNull(shared.background(), "unusable: the style decides");
		assertEquals(100, shared.backgroundOpacity(), "clamped");
		assertEquals(3, shared.padding(), "rounded");
		assertFalse(layout.module(ModuleId.FPS).enabled());
		assertFalse(layout.module(ModuleId.FPS).bool(ModuleSettings.Fps.SHOW_LOW));
		ModuleState keystrokes = layout.module(ModuleId.KEYSTROKES);
		assertTrue(keystrokes.enabled());
		assertEquals(24, keystrokes.integer(ModuleSettings.Keystrokes.KEY_SIZE));
		assertEquals(KeystrokesLayout.KEYS_MOUSE, keystrokes.choice(ModuleSettings.Keystrokes.LAYOUT), "the old jump switch, migrated");
		HudPlacement fallback = ModuleRegistry.get(ModuleId.KEYSTROKES).defaultPlacement();
		assertEquals(new HudPlacement(fallback.anchor(), fallback.x(), 12, 2.0F), keystrokes.placement(), "field by field");
		ModuleState armor = layout.module(ModuleId.ARMOR);
		assertEquals(DurabilityFormat.PERCENT, armor.choice(ModuleSettings.Armor.DURABILITY));
		assertEquals(Orientation.HORIZONTAL, armor.choice(ModuleSettings.Armor.LAYOUT));
		Appearance overrides = armor.appearance();
		assertEquals(HudThemeId.CLASSIC, overrides.theme());
		assertNull(overrides.textColor(), "not a palette colour: follow the layout");
		assertEquals(8, overrides.padding(), "clamped");
		assertNull(overrides.showLabel());
		assertFalse(layout.module(ModuleId.EFFECTS).enabled());
		assertEquals(ModuleRegistry.get(ModuleId.PING).defaultPlacement(), layout.module(ModuleId.PING).placement());
	}

	@Test
	void namesAreCleanedAndCapped() {
		Layout layout = LayoutCodec.read(JsonParser.parseString("{\"name\": \"  Way\\ntoo long a name for a layout, really  \"}")
			.getAsJsonObject(), "long");
		assertEquals("Way too long a name for a layout", layout.name(), "the line break becomes a space");
		assertEquals(32, layout.name().length());
	}

	@Test
	void anEmptyObjectIsTheDefaultLayout() {
		assertEquals(new Layout("x", "x"), LayoutCodec.read(new JsonObject(), "x"));
	}
}
