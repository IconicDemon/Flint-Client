package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Settings that replaced older ones read the older keys when the file has no value of its own: each migration, the
 * cases where it must not run, and what it does to the "Show label" override.
 */
class ModuleMigrationsTest {
	/** A module read as the layout reader reads it: its settings from {@code json}, then {@code overrides}, then the migration. */
	private static ModuleState read(String moduleId, String json, Appearance overrides) {
		ModuleState module = new ModuleState(ModuleRegistry.get(moduleId));
		JsonObject settings = JsonParser.parseString(json).getAsJsonObject();
		for (SettingDescriptor<?> setting : module.descriptor().settings()) {
			module.setFromJson(setting, settings.get(setting.id()));
		}
		module.setAppearance(overrides);
		ModuleMigrations.apply(module, settings);
		return module;
	}

	private static ModuleState read(String moduleId, String json) {
		return read(moduleId, json, Appearance.INHERIT);
	}

	@Test
	void cpsRightClicksBecomeTheButtonsChoice() {
		assertEquals(CpsButtons.BOTH, read(ModuleId.CPS, "{\"showRight\": true}").choice(ModuleSettings.Cps.BUTTONS));
		assertEquals(CpsButtons.LEFT, read(ModuleId.CPS, "{\"showRight\": false}").choice(ModuleSettings.Cps.BUTTONS));
		assertEquals(CpsButtons.COMBINED, read(ModuleId.CPS, "{\"showRight\": false, \"buttons\": \"combined\"}")
			.choice(ModuleSettings.Cps.BUTTONS), "a file that has the new setting keeps it");
		assertEquals(CpsButtons.LEFT, read(ModuleId.CPS, "{\"showRight\": false, \"buttons\": \"triple\"}")
			.choice(ModuleSettings.Cps.BUTTONS), "a value this version can't read doesn't count as having it");
		assertEquals(CpsButtons.BOTH, read(ModuleId.CPS, "{}").choice(ModuleSettings.Cps.BUTTONS), "nothing old: the default");
		assertEquals(CpsButtons.BOTH, read(ModuleId.CPS, "{\"showRight\": \"no\"}").choice(ModuleSettings.Cps.BUTTONS),
			"an unusable old value is no migration");
	}

	@Test
	void keystrokesMouseAndJumpSwitchesBecomeTheLayout() {
		assertEquals(KeystrokesLayout.KEYS_MOUSE_JUMP, layout("{\"showMouse\": true, \"showSpace\": true}"));
		assertEquals(KeystrokesLayout.KEYS_MOUSE, layout("{\"showMouse\": true, \"showSpace\": false}"));
		assertEquals(KeystrokesLayout.KEYS, layout("{\"showMouse\": false, \"showSpace\": false}"));
		assertEquals(KeystrokesLayout.KEYS, layout("{\"showMouse\": false, \"showSpace\": true}"),
			"jump without the mouse buttons has no layout: the mouse was turned off, so keys only");
		assertEquals(KeystrokesLayout.KEYS_MOUSE, layout("{\"showSpace\": false}"), "a missing old switch was on");
		assertEquals(KeystrokesLayout.KEYS, layout("{\"showMouse\": false}"));
		assertEquals(KeystrokesLayout.KEYS_MOUSE_JUMP, layout("{\"keySize\": 20}"));
		assertEquals(KeystrokesLayout.KEYS_MOUSE, layout("{\"showMouse\": false, \"layout\": \"keys_mouse\"}"));
	}

	private static KeystrokesLayout layout(String json) {
		return read(ModuleId.KEYSTROKES, json).choice(ModuleSettings.Keystrokes.LAYOUT);
	}

	@Test
	void directionAxisAndDegreesBecomeTheFormat() {
		assertEquals(DirectionFormat.DETAILED, format("{\"showAxis\": true, \"showDegrees\": true}"),
			"the old module always added both: it looked like Detailed");
		assertEquals(DirectionFormat.DETAILED, format("{\"showAxis\": false, \"showDegrees\": true}"));
		assertEquals(DirectionFormat.DETAILED, format("{\"showAxis\": true, \"showDegrees\": false}"));
		assertEquals(DirectionFormat.NAME, format("{\"showAxis\": false, \"showDegrees\": false}"), "both off was the name alone");
		assertEquals(DirectionFormat.DETAILED, format("{\"showAxis\": false}"), "a missing old switch was on");
		assertEquals(DirectionFormat.NAME, format("{}"), "a new module: the clean default");
		assertEquals(DirectionFormat.COMPACT, format("{\"showAxis\": true, \"showDegrees\": true, \"format\": \"compact\"}"));
		ModuleState kept = read(ModuleId.DIRECTION, "{\"showAxis\": false, \"showDegrees\": true}");
		assertFalse(kept.bool(ModuleSettings.Direction.SHOW_AXIS), "both stay as settings of the Detailed format");
		assertTrue(kept.bool(ModuleSettings.Direction.SHOW_DEGREES));
	}

	private static DirectionFormat format(String json) {
		return read(ModuleId.DIRECTION, json).choice(ModuleSettings.Direction.FORMAT);
	}

	@Test
	void showLabelOffHidesTheLabelOfModulesThatHaveAPosition() {
		Appearance off = Appearance.INHERIT.withShowLabel(false).withTextColor(HudColor.SKY);
		for (String id : List.of(ModuleId.PING, ModuleId.CPS)) {
			ModuleState module = read(id, "{}", off);
			assertEquals(LabelPosition.HIDDEN, module.choice(ModuleSettings.labelPosition(module.descriptor())), id);
			assertNull(module.appearance().showLabel(), id + ": the old switch is dropped");
			assertEquals(HudColor.SKY, module.appearance().textColor(), id + ": the other overrides stay");
		}
		ModuleState fps = read(ModuleId.FPS, "{}", off);
		assertFalse(fps.bool(ModuleSettings.Fps.SHOW_LABEL), "FPS hides its label with its own switch");
		assertFalse(fps.bool(ModuleSettings.Fps.REVERSED));
		assertNull(fps.appearance().showLabel(), "the old switch is dropped");
		assertEquals(HudColor.SKY.argb() & 0xFFFFFF, fps.rgb(ModuleSettings.Fps.TEXT_COLOR), "FPS keeps its text colour as its own");
		ModuleState coordinates = read(ModuleId.COORDINATES, "{}", off);
		assertFalse(coordinates.bool(ModuleSettings.Coordinates.LABELS), "the coordinates' X/Y/Z labels go off");
		assertNull(coordinates.appearance().showLabel());
	}

	@Test
	void showLabelOnBecomesTheDefaultPositionAndIsDropped() {
		ModuleState fps = read(ModuleId.FPS, "{\"showLow\": true}", Appearance.INHERIT.withShowLabel(true));
		assertTrue(fps.bool(ModuleSettings.Fps.SHOW_LABEL));
		assertFalse(fps.bool(ModuleSettings.Fps.REVERSED), "the label stays after the number");
		assertTrue(fps.appearance().isInherit());
		assertTrue(fps.bool(ModuleSettings.Fps.SHOW_LOW), "the kept setting is read as before");
	}

	@Test
	void aFileWithTheNewLabelSettingIsLeftAsItIs() {
		ModuleState fps = read(ModuleId.FPS, "{\"labelPosition\": \"before\"}", Appearance.INHERIT.withShowLabel(false));
		assertTrue(fps.bool(ModuleSettings.Fps.REVERSED), "FPS's older Before is Reversed");
		assertTrue(fps.bool(ModuleSettings.Fps.SHOW_LABEL), "its own label setting decided the label, never the old switch");
		assertNull(fps.appearance().showLabel(), "the switch it never read is dropped");
		ModuleState hidden = read(ModuleId.FPS, "{\"labelPosition\": \"hidden\"}", Appearance.INHERIT);
		assertFalse(hidden.bool(ModuleSettings.Fps.SHOW_LABEL), "FPS's older Hidden is the label off");
		assertTrue(ModuleMigrations.retiredKeys(fps.descriptor()).contains("labelPosition"), "the old key leaves the file on the next save");
		ModuleState coordinates = read(ModuleId.COORDINATES, "{\"labels\": true}", Appearance.INHERIT.withShowLabel(false));
		assertTrue(coordinates.bool(ModuleSettings.Coordinates.LABELS));
	}

	@Test
	void modulesThatKeepShowLabelKeepIt() {
		for (String id : List.of(ModuleId.DIRECTION, ModuleId.KEYSTROKES, ModuleId.ARMOR, ModuleId.EFFECTS)) {
			ModuleState module = read(id, "{}", Appearance.INHERIT.withShowLabel(false));
			assertEquals(false, module.appearance().showLabel(), id);
		}
	}

	@Test
	void noSettingsObjectAtAll() {
		ModuleState cps = new ModuleState(ModuleRegistry.get(ModuleId.CPS));
		cps.setAppearance(Appearance.INHERIT.withShowLabel(false));
		ModuleMigrations.apply(cps, null);
		assertEquals(CpsButtons.BOTH, cps.choice(ModuleSettings.Cps.BUTTONS));
		assertEquals(LabelPosition.HIDDEN, cps.choice(ModuleSettings.Cps.LABEL_POSITION));
	}

	@Test
	void retiredKeysAreListedPerModule() {
		assertEquals(List.of("showRight"), ModuleMigrations.retiredKeys(ModuleRegistry.get(ModuleId.CPS)));
		assertEquals(List.of("showMouse", "showSpace"), ModuleMigrations.retiredKeys(ModuleRegistry.get(ModuleId.KEYSTROKES)));
		assertTrue(ModuleMigrations.retiredKeys(ModuleRegistry.get(ModuleId.DIRECTION)).isEmpty(), "its old switches are still settings");
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			for (String retired : ModuleMigrations.retiredKeys(module)) {
				assertNull(module.setting(retired), "a retired key is no current setting: " + retired);
			}
		}
	}
}
