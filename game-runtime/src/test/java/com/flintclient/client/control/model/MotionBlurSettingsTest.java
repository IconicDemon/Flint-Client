package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.fx.BlurRules;
import com.flintclient.client.control.model.mods.VisualMods.MotionBlur;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Motion Blur's settings: Smooth at 50% by default, both rows always apply, and Flint 1.0.2's settings carried over. */
class MotionBlurSettingsTest {
	private static final String[] OLD_KEYS = {"blurAlgorithm", "motionBlurStrength", "refreshRateScaling", "blurProfile", "quality",
		"movementBlur"};

	private static ModuleState motionBlur() {
		return new ModuleState(ModuleRegistry.get(MotionBlur.ID));
	}

	private static ModuleState migrated(JsonObject saved) {
		ModuleState m = motionBlur();
		for (SettingDescriptor<?> s : m.descriptor().settings()) {
			if (saved.has(s.id())) {
				m.setFromJson(s, saved.get(s.id()));
			}
		}
		ModuleMigrations.apply(m, saved);
		return m;
	}

	@Test
	void offWithSmoothAtHalfStrength() {
		ModuleState m = motionBlur();
		assertFalse(m.enabled(), "every mod starts off");
		assertEquals(BlurRules.SMOOTH, m.option(MotionBlur.MODE));
		assertEquals(50, m.integer(MotionBlur.STRENGTH));
		assertTrue(m.descriptor().settings().contains(MotionBlur.MODE));
		assertTrue(m.descriptor().settings().contains(MotionBlur.STRENGTH));
		for (String old : OLD_KEYS) {
			assertNull(m.descriptor().setting(old), old + " is retired");
		}
	}

	@Test
	void theModesAreInBlurRulesOrder() {
		assertEquals(BlurRules.SMOOTH, MotionBlur.MODE.indexOf("smooth"));
		assertEquals(BlurRules.NATURAL, MotionBlur.MODE.indexOf("natural"));
	}

	@Test
	void bothRowsAlwaysApply() {
		ModuleState m = motionBlur();
		for (int mode : new int[] {BlurRules.SMOOTH, BlurRules.NATURAL}) {
			m.set(MotionBlur.MODE, mode);
			assertNull(ModuleSettings.inactiveReason(m, MotionBlur.MODE));
			assertNull(ModuleSettings.inactiveReason(m, MotionBlur.STRENGTH));
		}
	}

	@Test
	void anOlderAlgorithmIsSmoothAndTheStrengthCarriesOver() {
		for (String algorithm : new String[] {"velocity_based", "frame_blending", "hybrid_blending", "accumulation_max", "accumulation_mix",
			"classic"}) {
			JsonObject saved = new JsonObject();
			saved.add("blurAlgorithm", new JsonPrimitive(algorithm));
			saved.add("motionBlurStrength", new JsonPrimitive(1.0));
			ModuleState m = migrated(saved);
			assertEquals(BlurRules.SMOOTH, m.option(MotionBlur.MODE), algorithm);
			assertEquals(50, m.integer(MotionBlur.STRENGTH), "1.00 was the default: 50%");
		}
		JsonObject strong = new JsonObject();
		strong.add("motionBlurStrength", new JsonPrimitive(2.0));
		assertEquals(100, migrated(strong).integer(MotionBlur.STRENGTH));
		JsonObject weak = new JsonObject();
		weak.add("motionBlurStrength", new JsonPrimitive(0.37));
		assertEquals(20, migrated(weak).integer(MotionBlur.STRENGTH), "to the nearest 5%");
		JsonObject zero = new JsonObject();
		zero.add("motionBlurStrength", new JsonPrimitive(0));
		assertEquals(5, migrated(zero).integer(MotionBlur.STRENGTH), "the weakest the slider goes");
		assertEquals(50, migrated(new JsonObject()).integer(MotionBlur.STRENGTH), "nothing saved: the default");
	}

	@Test
	void aNewerStrengthWinsAndOldKeysLeaveTheFile() {
		JsonObject saved = new JsonObject();
		saved.add("motionBlurStrength", new JsonPrimitive(2.0));
		saved.add("strength", new JsonPrimitive(35));
		saved.add("mode", new JsonPrimitive("natural"));
		ModuleState m = migrated(saved);
		assertEquals(35, m.integer(MotionBlur.STRENGTH));
		assertEquals(BlurRules.NATURAL, m.option(MotionBlur.MODE));
		List<String> retired = ModuleMigrations.retiredKeys(m.descriptor());
		for (String key : OLD_KEYS) {
			assertTrue(retired.contains(key), key);
		}
	}
}
