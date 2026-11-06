package com.flintclient.client.control.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.fx.NmbTiming;
import com.flintclient.client.control.model.mods.VisualMods.MotionBlur;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

/** Motion Blur's settings: Natural Motion Blur's defaults, the rows it greys out, and older saved values. */
class MotionBlurSettingsTest {
	private static ModuleState motionBlur() {
		return new ModuleState(ModuleRegistry.get(MotionBlur.ID));
	}

	@Test
	void offWithNaturalMotionBlursRecommendedSettings() {
		ModuleState m = motionBlur();
		assertFalse(m.enabled(), "every mod starts off");
		assertEquals(NmbTiming.VELOCITY, m.option(MotionBlur.ALGORITHM));
		assertEquals(1, m.decimal(MotionBlur.STRENGTH), 1e-6);
		assertTrue(m.bool(MotionBlur.REFRESH_SCALING));
		assertEquals(0, m.option(MotionBlur.PROFILE), "Uniform");
		assertEquals(NmbTiming.BALANCED, m.option(MotionBlur.QUALITY));
		assertTrue(m.bool(MotionBlur.MOVEMENT));
	}

	@Test
	void theOptionsAreInNmbTimingsOrder() {
		assertEquals(NmbTiming.VELOCITY, MotionBlur.ALGORITHM.indexOf("velocity_based"));
		assertEquals(NmbTiming.FRAME_BLENDING, MotionBlur.ALGORITHM.indexOf("frame_blending"));
		assertEquals(NmbTiming.HYBRID, MotionBlur.ALGORITHM.indexOf("hybrid_blending"));
		assertEquals(NmbTiming.ACCUMULATION_MAX, MotionBlur.ALGORITHM.indexOf("accumulation_max"));
		assertEquals(NmbTiming.ACCUMULATION_MIX, MotionBlur.ALGORITHM.indexOf("accumulation_mix"));
		assertEquals(NmbTiming.PERFORMANCE, MotionBlur.QUALITY.indexOf("performance"));
		assertEquals(NmbTiming.BALANCED, MotionBlur.QUALITY.indexOf("balanced"));
		assertEquals(NmbTiming.HIGH, MotionBlur.QUALITY.indexOf("high"));
	}

	@Test
	void theRetiredClassicTrailReadsAsVelocityBased() {
		ModuleState m = motionBlur();
		m.setFromJson(MotionBlur.ALGORITHM, new JsonPrimitive("classic"));
		assertEquals(NmbTiming.VELOCITY, m.option(MotionBlur.ALGORITHM));
		m.setFromJson(MotionBlur.ALGORITHM, new JsonPrimitive("accumulation_mix"));
		assertEquals(NmbTiming.ACCUMULATION_MIX, m.option(MotionBlur.ALGORITHM));
	}

	@Test
	void rowsTheAlgorithmDoesntUseSayWhy() {
		ModuleState m = motionBlur();
		for (SettingDescriptor<?> s : m.descriptor().settings()) {
			if (s == MotionBlur.ALGORITHM || s == MotionBlur.STRENGTH || s == MotionBlur.REFRESH_SCALING || s == MotionBlur.PROFILE
				|| s == MotionBlur.QUALITY || s == MotionBlur.MOVEMENT) {
				assertNull(ModuleSettings.inactiveReason(m, s), "Velocity based uses " + s.id());
			}
		}
		m.set(MotionBlur.ALGORITHM, NmbTiming.HYBRID);
		assertNotNull(ModuleSettings.inactiveReason(m, MotionBlur.REFRESH_SCALING));
		assertNull(ModuleSettings.inactiveReason(m, MotionBlur.PROFILE));
		assertNull(ModuleSettings.inactiveReason(m, MotionBlur.QUALITY));
		assertNull(ModuleSettings.inactiveReason(m, MotionBlur.MOVEMENT));
		for (int algorithm : new int[] {NmbTiming.FRAME_BLENDING, NmbTiming.ACCUMULATION_MAX, NmbTiming.ACCUMULATION_MIX}) {
			m.set(MotionBlur.ALGORITHM, algorithm);
			assertNotNull(ModuleSettings.inactiveReason(m, MotionBlur.REFRESH_SCALING));
			assertNotNull(ModuleSettings.inactiveReason(m, MotionBlur.PROFILE));
			assertNotNull(ModuleSettings.inactiveReason(m, MotionBlur.QUALITY));
			assertNotNull(ModuleSettings.inactiveReason(m, MotionBlur.MOVEMENT));
			assertNull(ModuleSettings.inactiveReason(m, MotionBlur.STRENGTH), "every algorithm has a strength");
		}
	}
}
