package com.flintclient.legacy.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.KeySetting;
import com.flintclient.shaded.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.lwjgl.input.Keyboard;

class KeyNames189Test {
	@Test
	void flintsDefaultKeysHaveTheirSharedNames() {
		assertEquals("key.keyboard.right.shift", KeyNames189.name(Keyboard.KEY_RSHIFT));
		assertEquals("key.keyboard.c", KeyNames189.name(Keyboard.KEY_C));
		assertEquals("key.keyboard.n", KeyNames189.name(Keyboard.KEY_N));
		assertEquals("key.keyboard.grave.accent", KeyNames189.name(Keyboard.KEY_GRAVE));
		assertEquals("key.keyboard.f6", KeyNames189.name(Keyboard.KEY_F6));
		assertEquals("key.keyboard.keypad.5", KeyNames189.name(Keyboard.KEY_NUMPAD5));
		assertEquals("key.keyboard.left.control", KeyNames189.name(Keyboard.KEY_LCONTROL));
		assertEquals("key.mouse.left", KeyNames189.name(-100));
		assertEquals("key.mouse.middle", KeyNames189.name(-98));
		assertEquals("key.mouse.4", KeyNames189.name(-97));
	}

	@Test
	void noneAndUnknownKeysAreNone() {
		assertEquals("", KeyNames189.name(0));
		assertEquals("", KeyNames189.name(Keyboard.KEY_KANA));
		assertEquals(0, KeyNames189.code(""));
		assertEquals(0, KeyNames189.code(null));
		assertEquals(0, KeyNames189.code("key.keyboard.world.1"));
	}

	@Test
	void everyKeyRoundTripsAndIsAValidSharedKeyName() {
		KeySetting check = new KeySetting("k", "K", null, KeySetting.NONE);
		int named = 0;
		for (int code = -110; code < 256; code++) {
			String name = KeyNames189.name(code);
			if (name.isEmpty()) {
				continue;
			}
			named++;
			assertEquals(code, KeyNames189.code(name), name);
			assertEquals(name, check.parse(new JsonPrimitive(name)), "the shared setting accepts " + name);
		}
		assertTrue(named > 100, "only " + named + " keys have names");
	}
}
