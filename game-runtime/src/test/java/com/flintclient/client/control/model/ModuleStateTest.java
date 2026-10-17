package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class ModuleStateTest {
	private static ModuleState keystrokes() {
		return new ModuleState(ModuleRegistry.get(ModuleId.KEYSTROKES));
	}

	@Test
	void startsOffWithEveryDefault() {
		ModuleState state = keystrokes();
		assertFalse(state.enabled());
		assertEquals(KeystrokesLayout.KEYS_MOUSE_JUMP, state.choice(ModuleSettings.Keystrokes.LAYOUT));
		assertFalse(state.bool(ModuleSettings.Keystrokes.SHOW_CPS));
		assertEquals(16, state.integer(ModuleSettings.Keystrokes.KEY_SIZE));
		assertEquals(16, state.get(ModuleSettings.Keystrokes.KEY_SIZE));
		assertEquals(ModuleRegistry.get(ModuleId.KEYSTROKES).defaultPlacement(), state.placement());
		assertEquals(Appearance.INHERIT, state.appearance());
		assertEquals(0, state.revision());
		assertEquals("keystrokes", state.id());
	}

	@Test
	void settersClampAndCountRealChangesOnly() {
		ModuleState state = keystrokes();
		assertTrue(state.set(ModuleSettings.Keystrokes.KEY_SIZE, 40));
		assertEquals(24, state.integer(ModuleSettings.Keystrokes.KEY_SIZE), "clamped to the maximum");
		int revision = state.revision();
		assertFalse(state.set(ModuleSettings.Keystrokes.KEY_SIZE, 30), "clamps to the same value: no change");
		assertEquals(revision, state.revision());
		assertTrue(state.setEnabled(true));
		assertFalse(state.setEnabled(true));
		assertTrue(state.setPlacement(HudPlacement.of(Anchor.CENTER, 0, 0)));
		assertFalse(state.setPlacement(HudPlacement.of(Anchor.CENTER, 0, 0)));
		assertTrue(state.setAppearance(Appearance.INHERIT.withPadding(5)));
		assertEquals(revision + 3, state.revision());
	}

	@Test
	void choicesAndFileValues() {
		ModuleState coordinates = new ModuleState(ModuleRegistry.get(ModuleId.COORDINATES));
		coordinates.set(ModuleSettings.Coordinates.DECIMALS, Decimals.ONE);
		assertEquals(Decimals.ONE, coordinates.choice(ModuleSettings.Coordinates.DECIMALS));
		coordinates.setFromJson(ModuleSettings.Coordinates.LAYOUT, new JsonPrimitive("horizontal"));
		assertEquals(CoordinatesLayout.HORIZONTAL, coordinates.choice(ModuleSettings.Coordinates.LAYOUT));
		coordinates.setFromJson(ModuleSettings.Coordinates.LAYOUT, new JsonPrimitive("diagonal"));
		assertEquals(CoordinatesLayout.VERTICAL, coordinates.choice(ModuleSettings.Coordinates.LAYOUT), "unusable means the default");
		coordinates.setFromJson(ModuleSettings.Coordinates.OTHER_DIMENSION, null);
		assertFalse(coordinates.bool(ModuleSettings.Coordinates.OTHER_DIMENSION));
	}

	@Test
	void settingsOfAnotherModuleAreRefused() {
		ModuleState state = keystrokes();
		assertThrows(IllegalArgumentException.class, () -> state.bool(ModuleSettings.Fps.SHOW_LOW));
		assertThrows(IllegalArgumentException.class, () -> state.set(ModuleSettings.Fps.SHOW_LOW, true));
		assertThrows(IllegalArgumentException.class,
			() -> state.copyFrom(new ModuleState(ModuleRegistry.get(ModuleId.FPS))));
	}

	@Test
	void resetRestoresSettingsAndAppearanceButNotPlacementOrEnabled() {
		ModuleState state = keystrokes();
		HudPlacement moved = HudPlacement.of(Anchor.BOTTOM_RIGHT, 10, 12).withScale(1.5F);
		state.setEnabled(true);
		state.setPlacement(moved);
		state.set(ModuleSettings.Keystrokes.LAYOUT, KeystrokesLayout.KEYS);
		state.set(ModuleSettings.Keystrokes.KEY_SIZE, 20);
		state.setAppearance(new Appearance(HudThemeId.CLASSIC, HudColor.SKY, false, 30, 1, false));
		assertTrue(state.reset());
		assertEquals(KeystrokesLayout.KEYS_MOUSE_JUMP, state.choice(ModuleSettings.Keystrokes.LAYOUT));
		assertEquals(16, state.integer(ModuleSettings.Keystrokes.KEY_SIZE));
		assertEquals(Appearance.INHERIT, state.appearance());
		assertEquals(moved, state.placement(), "the position is reset separately");
		assertTrue(state.enabled());
		assertFalse(state.reset(), "nothing left to reset");
		assertTrue(state.resetPlacement());
		assertEquals(ModuleRegistry.get(ModuleId.KEYSTROKES).defaultPlacement(), state.placement());
	}

	@Test
	void copiesAreEqualAndIndependent() {
		ModuleState state = keystrokes();
		state.setEnabled(true);
		state.set(ModuleSettings.Keystrokes.KEY_SIZE, 18);
		ModuleState copy = state.copy();
		assertEquals(state, copy);
		assertEquals(state.hashCode(), copy.hashCode());
		copy.set(ModuleSettings.Keystrokes.KEY_SIZE, 22);
		assertNotEquals(state, copy);
		assertEquals(18, state.integer(ModuleSettings.Keystrokes.KEY_SIZE));
		assertFalse(state.copyFrom(state.copy()), "copying identical state is no change");
	}

	@Test
	void appearanceLayersOverridesOverTheLayoutOverDefaults() {
		Appearance layout = new Appearance(HudThemeId.CLASSIC, HudColor.SAND, null, 40, null, null);
		Appearance module = Appearance.INHERIT.withTextColor(HudColor.MINT).withShowLabel(false);
		Appearance effective = Appearance.effective(module, layout);
		assertEquals(HudThemeId.CLASSIC, effective.theme(), "from the layout");
		assertEquals(HudColor.MINT, effective.textColor(), "the module's override wins");
		assertEquals(true, effective.background(), "from the defaults");
		assertEquals(40, effective.backgroundOpacity());
		assertEquals(3, effective.padding());
		assertEquals(false, effective.showLabel());
		assertEquals(true, Appearance.effective(Appearance.INHERIT, layout).showLabel(), "labels are on by default");
		assertTrue(Appearance.INHERIT.isInherit());
		assertFalse(module.isInherit());
		assertEquals(Appearance.DEFAULTS.withBackground(true).withBackgroundOpacity(70).withAccentColor(HudColor.DEFAULT),
			Appearance.effective(Appearance.INHERIT, Appearance.INHERIT),
			"the defaults, with Flint's panel");
	}

	@Test
	void appearanceNumbersAreClamped() {
		Appearance appearance = new Appearance(null, null, null, 250, -3, null);
		assertEquals(100, appearance.backgroundOpacity());
		assertEquals(0, appearance.padding());
		assertNull(Appearance.DEFAULTS.backgroundOpacity(), "the style decides");
		assertEquals(3, Appearance.DEFAULTS.padding());
		assertEquals(HudThemeId.FLINT, Appearance.DEFAULTS.theme());
		assertEquals(HudColor.DEFAULT, Appearance.DEFAULTS.textColor());
		assertNull(Appearance.DEFAULTS.background(), "the style decides");
		assertEquals(70, HudThemeId.FLINT.defaultBackgroundOpacity());
		assertEquals(40, HudThemeId.CLASSIC.defaultBackgroundOpacity(), "like Minecraft's chat background");
		assertFalse(HudThemeId.MINIMAL.defaultBackground(), "Minimal has no panel");
	}
}
