package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.mods.HudMods;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

/** Option, key and text settings, and the shared boxed-counter look. */
class NewSettingTypesTest {
	private static final OptionSetting MODE = new OptionSetting("mode", "Mode", null, 1, "hold", "Hold", "toggle", "Toggle", "off", "Off");

	@Test
	void optionsAreSavedByKeyAndReadInAnyCase() {
		assertEquals(new JsonPrimitive("toggle"), MODE.toJson(1));
		assertEquals(0, MODE.parse(new JsonPrimitive("HOLD")));
		assertEquals(2, MODE.parse(new JsonPrimitive(" off ")));
		assertEquals(1, MODE.parse(new JsonPrimitive("sideways")), "unknown: the default");
		assertEquals(1, MODE.parse(new JsonPrimitive(2)), "an index isn't a key");
		assertEquals(1, MODE.clamp(7));
		assertTrue(MODE.valid(2));
		assertFalse(MODE.valid(3));
		assertEquals(java.util.List.of("Hold", "Toggle", "Off"), MODE.labels());
		assertThrows(IllegalArgumentException.class, () -> new OptionSetting("x", "X", null, 0, "only", "Only"));
		assertThrows(IllegalArgumentException.class, () -> new OptionSetting("x", "X", null, 5, "a", "A", "b", "B"));
	}

	@Test
	void keysAreMinecraftsVersionNeutralNames() {
		KeySetting key = new KeySetting("key", "Key", null, "key.keyboard.c");
		assertEquals("key.keyboard.left.alt", key.parse(new JsonPrimitive("key.keyboard.left.alt")));
		assertEquals("key.mouse.middle", key.parse(new JsonPrimitive("key.mouse.middle")));
		assertEquals("", key.parse(new JsonPrimitive("")), "no key");
		assertEquals("key.keyboard.c", key.parse(new JsonPrimitive("C")), "not a key name: the default");
		assertEquals("key.keyboard.c", key.parse(JsonNull.INSTANCE));
		assertThrows(IllegalArgumentException.class, () -> new KeySetting("k", "K", null, "ctrl+c"));
	}

	@Test
	void textIsCleanedAndCut() {
		TextSetting text = new TextSetting("nick", "Nick", null, "You", 8);
		assertEquals("Hello", text.parse(new JsonPrimitive("He\u0007llo")));
		assertEquals("Redtext", text.parse(new JsonPrimitive("\u00A7cRed\u00A7rtext")), "formatting codes are removed");
		assertEquals("12345678", text.parse(new JsonPrimitive("123456789")));
		assertEquals("You", text.parse(new JsonPrimitive(5)));
		assertTrue(text.valid("Hi"));
		assertFalse(text.valid("Hi\n"));
	}

	@Test
	void moduleStateReadsTheNewTypes() {
		Layout layout = new Layout("a", "A");
		ModuleState zoom = layout.module(com.flintclient.client.control.model.mods.PlayerMods.Zoom.ID);
		assertEquals("key.keyboard.c", zoom.text(com.flintclient.client.control.model.mods.PlayerMods.Zoom.KEY));
		assertEquals(0, zoom.option(com.flintclient.client.control.model.mods.PlayerMods.Zoom.MODE));
		assertTrue(zoom.set(com.flintclient.client.control.model.mods.PlayerMods.Zoom.MODE, 1));
		assertEquals(1, zoom.option(com.flintclient.client.control.model.mods.PlayerMods.Zoom.MODE));
	}

	@Test
	void everyBoxedCounterHasTheSameStyleIdsAndRules() {
		ModuleState memory = new ModuleState(HudMods.MemoryUsage.DESCRIPTOR);
		BoxStyle style = HudMods.MemoryUsage.STYLE;
		for (SettingDescriptor<?> setting : style.main()) {
			assertNotNull(memory.descriptor().setting(setting.id()), setting.id());
		}
		assertNotNull(ModuleSettings.inactiveReason(memory, style.width), "Dynamic sizes itself");
		memory.set(style.backgroundType, FpsSizing.STATIC);
		assertNull(ModuleSettings.inactiveReason(memory, style.width));
		memory.set(style.displayMode, FpsDisplayMode.TEXT);
		assertNotNull(ModuleSettings.inactiveReason(memory, style.border), "no box in Just Text");
		assertNull(ModuleSettings.inactiveReason(memory, style.textColor));
		assertTrue(style.owns(style.cornerRadius));
		assertFalse(style.owns(HudMods.MemoryUsage.FORMAT));
	}
}
