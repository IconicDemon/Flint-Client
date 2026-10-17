package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.Anchor;
import com.flintclient.client.control.hud.HudPlacement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

/** The FPS module's settings: colours, defaults, when a setting doesn't apply, and a reset that touches FPS only. */
class FpsSettingsTest {
	@Test
	void anyColourIsStoredAsHexAndBadValuesFallBack() {
		RgbSetting color = ModuleSettings.Fps.TEXT_COLOR;
		assertEquals(0xFF8139, color.parse(new JsonPrimitive("#FF8139")));
		assertEquals(0xFF8139, color.parse(new JsonPrimitive("ff8139")), "no hash, lower case");
		assertEquals(0xFFAA33, color.parse(new JsonPrimitive(" #fa3 ")), "short form, spaces around");
		for (String bad : new String[] {"#GG0000", "#12345", "red", "", "#1234567"}) {
			assertEquals(color.defaultValue(), color.parse(new JsonPrimitive(bad)), bad);
		}
		assertEquals(color.defaultValue(), color.parse(new JsonPrimitive(16777215)), "a number isn't a colour here");
		assertEquals(color.defaultValue(), color.parse(JsonNull.INSTANCE));
		assertEquals(color.defaultValue(), color.parse(null));
		assertEquals(new JsonPrimitive("#00FF7F"), color.toJson(0x00FF7F));
		assertEquals("#000000", RgbSetting.hex(0));
		assertTrue(color.valid(0xFFFFFF));
		assertFalse(color.valid(0x1000000), "no alpha");
		assertEquals(-1, RgbSetting.parseHex(null));
	}

	@Test
	void defaultsAreACleanBoxedCounter() {
		ModuleState fps = new ModuleState(ModuleRegistry.get(ModuleId.FPS));
		assertEquals(FpsDisplayMode.BACKGROUND, fps.choice(ModuleSettings.Fps.DISPLAY_MODE));
		assertTrue(fps.bool(ModuleSettings.Fps.BACKGROUND));
		assertEquals(FpsSizing.DYNAMIC, fps.choice(ModuleSettings.Fps.BACKGROUND_TYPE));
		assertFalse(fps.bool(ModuleSettings.Fps.BORDER));
		assertEquals(0xFFFFFF, fps.rgb(ModuleSettings.Fps.TEXT_COLOR));
		assertTrue(fps.bool(ModuleSettings.Fps.TEXT_SHADOW));
		assertFalse(fps.bool(ModuleSettings.Fps.REVERSED), "80 FPS, not FPS 80");
		assertTrue(fps.bool(ModuleSettings.Fps.SHOW_LABEL));
		assertEquals(45, fps.integer(ModuleSettings.Fps.BACKGROUND_OPACITY));
		assertEquals("Display your frames per second", fps.descriptor().description());
	}

	@Test
	void settingsThatDontApplySayWhy() {
		ModuleState fps = new ModuleState(ModuleRegistry.get(ModuleId.FPS));
		assertNotNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.WIDTH), "Dynamic sizes itself");
		assertNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.HEIGHT), "the height applies in Dynamic too");
		fps.set(ModuleSettings.Fps.BACKGROUND_TYPE, FpsSizing.STATIC);
		assertNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.WIDTH));
		assertNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.ALIGN));
		assertNotNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.BORDER_COLOR), "the border is off");
		fps.set(ModuleSettings.Fps.BORDER, true);
		assertNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.BORDER_COLOR));
		fps.set(ModuleSettings.Fps.BACKGROUND, false);
		assertNotNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.BACKGROUND_OPACITY));
		assertNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.CORNER_RADIUS), "the border still has corners");
		for (FpsDisplayMode mode : new FpsDisplayMode[] {FpsDisplayMode.BRACKETS, FpsDisplayMode.TEXT}) {
			fps.set(ModuleSettings.Fps.DISPLAY_MODE, mode);
			for (SettingDescriptor<?> box : new SettingDescriptor<?>[] {ModuleSettings.Fps.BACKGROUND, ModuleSettings.Fps.BACKGROUND_TYPE,
				ModuleSettings.Fps.WIDTH, ModuleSettings.Fps.HEIGHT, ModuleSettings.Fps.BORDER, ModuleSettings.Fps.BACKGROUND_COLOR}) {
				assertNotNull(ModuleSettings.inactiveReason(fps, box), box.id() + " has no box in " + mode);
			}
			assertNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.TEXT_COLOR), "the text always applies");
			assertNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.REVERSED));
		}
		fps.set(ModuleSettings.Fps.SHOW_LABEL, false);
		assertNotNull(ModuleSettings.inactiveReason(fps, ModuleSettings.Fps.REVERSED), "no label to move");
	}

	@Test
	void resetPutsBackOnlyThisModulesSettings() {
		Layout layout = new Layout("a", "A");
		ModuleState fps = layout.module(ModuleId.FPS);
		ModuleState ping = layout.module(ModuleId.PING);
		HudPlacement moved = HudPlacement.of(Anchor.TOP_RIGHT, 30, 40).withScale(1.5F);
		fps.setEnabled(true);
		fps.setPlacement(moved);
		fps.set(ModuleSettings.Fps.DISPLAY_MODE, FpsDisplayMode.BRACKETS);
		fps.set(ModuleSettings.Fps.TEXT_COLOR, 0x00FF00);
		fps.set(ModuleSettings.Fps.WIDTH, 100);
		fps.set(ModuleSettings.Fps.REVERSED, true);
		ping.set(ModuleSettings.Ping.SHOW_BARS, true);
		assertTrue(fps.reset());
		assertEquals(FpsDisplayMode.BACKGROUND, fps.choice(ModuleSettings.Fps.DISPLAY_MODE));
		assertEquals(0xFFFFFF, fps.rgb(ModuleSettings.Fps.TEXT_COLOR));
		assertEquals(56, fps.integer(ModuleSettings.Fps.WIDTH));
		assertFalse(fps.bool(ModuleSettings.Fps.REVERSED));
		assertTrue(fps.enabled(), "on stays on");
		assertEquals(moved, fps.placement(), "the position and size stay (Reset position is its own button)");
		assertTrue(ping.bool(ModuleSettings.Ping.SHOW_BARS), "other modules are untouched");
		assertTrue(fps.resetPlacement());
		assertEquals(fps.descriptor().defaultPlacement(), fps.placement());
	}

	@Test
	void numbersAreClampedToTheirRange() {
		ModuleState fps = new ModuleState(ModuleRegistry.get(ModuleId.FPS));
		assertEquals(160, ModuleSettings.Fps.WIDTH.parse(new JsonPrimitive(9999)));
		assertEquals(9, ModuleSettings.Fps.HEIGHT.parse(new JsonPrimitive(-5)));
		assertEquals(4, ModuleSettings.Fps.BORDER_THICKNESS.parse(new JsonPrimitive(12)));
		assertTrue(fps.set(ModuleSettings.Fps.BACKGROUND_OPACITY, 103));
		assertEquals(100, fps.integer(ModuleSettings.Fps.BACKGROUND_OPACITY));
	}
}
