package com.flintclient.client.control.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.FogCustomizer;
import org.junit.jupiter.api.Test;

/**
 * Fog Customizer's fog: the defaults keep the fog they always gave; No fog and every multiplier give finite distances past
 * what Minecraft draws, never huge numbers or infinity; the sky's fog stays put while they change.
 */
class FogPlanTest {
	private static final float VIEW = FogPlan.view(12);
	/** Minecraft 26.x's own fog in the open at 12 chunks: the overworld's environmental fog and the render-distance fog. */
	private static final float VANILLA_ENV_START = 0;
	private static final float VANILLA_ENV_END = 1024;
	private static final float VANILLA_RD_START = VIEW - 19.2F;
	private static final float VANILLA_SKY_END = VIEW;
	private static final float VANILLA_CLOUD_END = 128;

	private static ModuleState settings() {
		return new ModuleState(ModuleRegistry.get(FogCustomizer.ID));
	}

	private static FogPlan.Distances vanilla() {
		FogPlan.Distances d = new FogPlan.Distances();
		d.environmentalStart = VANILLA_ENV_START;
		d.environmentalEnd = VANILLA_ENV_END;
		d.renderDistanceStart = VANILLA_RD_START;
		d.renderDistanceEnd = VIEW;
		d.skyEnd = VANILLA_SKY_END;
		d.cloudEnd = VANILLA_CLOUD_END;
		return d;
	}

	private static FogPlan.Distances air(ModuleState s) {
		FogPlan.Distances d = vanilla();
		FogPlan.adjust(d, FogPlan.Medium.AIR, false, "minecraft:overworld", VIEW, s, false);
		return d;
	}

	private static void assertSane(FogPlan.Distances d) {
		float limit = FogPlan.limit(VIEW);
		for (float v : new float[] {d.environmentalStart, d.environmentalEnd, d.renderDistanceStart, d.renderDistanceEnd, d.skyEnd, d.cloudEnd}) {
			assertTrue(Float.isFinite(v), "finite: " + v);
			assertTrue(Math.abs(v) <= limit, "within the limit: " + v);
		}
		assertTrue(d.environmentalEnd >= d.environmentalStart, "environmental end at or past its start");
		assertTrue(d.renderDistanceEnd >= d.renderDistanceStart, "render-distance end at or past its start");
	}

	@Test
	void theDefaultsKeepTheFogTheyGave() {
		FogPlan.Distances d = air(settings());
		// Default config: Linear from 25% to 100% of the render distance, for both fogs; the multiplier at 100%.
		assertEquals(VIEW * 0.25F, d.environmentalStart, 1e-3F);
		assertEquals(VIEW, d.environmentalEnd, 1e-3F);
		assertEquals(VIEW * 0.25F, d.renderDistanceStart, 1e-3F);
		assertEquals(VIEW, d.renderDistanceEnd, 1e-3F);
		assertEquals(VIEW, d.skyEnd, "the sky's fog reaches the render distance");
		assertEquals(VANILLA_CLOUD_END, d.cloudEnd, "the clouds keep Minecraft's");
		assertSane(d);
	}

	@Test
	void noFogIsFinitePastEverythingDrawn() {
		ModuleState s = settings();
		s.set(FogCustomizer.NO_FOG, true);
		FogPlan.Distances d = air(s);
		float far = VIEW * 4;
		assertTrue(d.environmentalStart >= far * 4, "past the far plane, wide-view corners too: " + d.environmentalStart);
		assertTrue(d.renderDistanceStart >= far * 4);
		assertTrue(d.environmentalEnd > d.environmentalStart, "a ramp, not a step (shader packs divide by it)");
		assertTrue(d.environmentalEnd <= 1_000_000, "a small number for a float: " + d.environmentalEnd);
		assertEquals(VIEW, d.skyEnd, "No fog leaves the sky's fog");
		assertEquals(VANILLA_CLOUD_END, d.cloudEnd, "No fog leaves the clouds' fog");
		assertSane(d);
	}

	@Test
	void noFogCoversTheWorldsHeightAtASmallRenderDistance() {
		float view = FogPlan.view(2);
		assertTrue(FogPlan.clearStart(view) >= 4096, "a 384-block world seen from its top at 2 chunks stays clear");
		assertTrue(FogPlan.limit(FogPlan.view(32)) <= 20_000, "and stays small at 32 chunks");
	}

	@Test
	void theMultiplierScalesTheFogAndNotTheSky() {
		for (int percent : new int[] {50, 150, 200, 250, 300}) {
			ModuleState s = settings();
			s.set(FogCustomizer.DISTANCE, percent);
			FogPlan.Distances d = air(s);
			float k = percent / 100.0F;
			assertEquals(VIEW * 0.25F * k, d.environmentalStart, 1e-3F, percent + "%");
			assertEquals(VIEW * k, d.environmentalEnd, 1e-3F, percent + "%");
			assertEquals(VIEW * k, d.renderDistanceEnd, 1e-3F, percent + "%");
			assertEquals(VIEW, d.skyEnd, percent + "%: the sky's fog stays put");
			assertEquals(VANILLA_CLOUD_END, d.cloudEnd, percent + "%: the clouds' too");
			assertSane(d);
		}
	}

	@Test
	void theLargestSettingsStayBounded() {
		ModuleState s = settings();
		s.set(FogCustomizer.DISTANCE, 300);
		s.set(FogCustomizer.DEFAULT.linearStart(), 200F);
		s.set(FogCustomizer.DEFAULT.linearEnd(), 200F);
		assertSane(air(s));
		for (int mode = 1; mode <= 3; mode++) {
			ModuleState m = settings();
			m.set(FogCustomizer.DISTANCE, 300);
			m.set(FogCustomizer.DEFAULT.type(), mode);
			m.set(FogCustomizer.DEFAULT.exp(), 0F);
			m.set(FogCustomizer.DEFAULT.exp2(), 0F);
			FogPlan.Distances d = air(m);
			assertSane(d);
			assertEquals(FogPlan.limit(VIEW), d.environmentalEnd, "mode " + mode + ": no fog, at the limit rather than past it");
		}
	}

	@Test
	void minecraftsOwnFogWithTheMultiplierStaysBounded() {
		ModuleState s = settings();
		s.set(FogCustomizer.DEFAULT.enabled(), false);
		s.set(FogCustomizer.DISTANCE, 300);
		FogPlan.Distances d = air(s);
		assertEquals(VANILLA_ENV_END * 3, d.environmentalEnd, 1e-3F, "Minecraft's fog, three times as far");
		assertEquals(VIEW * 3, d.renderDistanceEnd, 1e-3F);
		assertEquals(VIEW, d.skyEnd);
		assertSane(d);
	}

	@Test
	void hugeOrBrokenDistancesAreBroughtBack() {
		FogPlan.Distances d = vanilla();
		d.environmentalStart = Float.MAX_VALUE / 4;
		d.environmentalEnd = Float.POSITIVE_INFINITY;
		d.renderDistanceStart = Float.NaN;
		d.renderDistanceEnd = Float.NEGATIVE_INFINITY;
		d.skyEnd = Float.MAX_VALUE;
		d.cloudEnd = Float.NaN;
		FogPlan.settle(d, VIEW);
		assertSane(d);
	}

	@Test
	void anEndBeforeTheStartMovesToTheStart() {
		ModuleState s = settings();
		s.set(FogCustomizer.DEFAULT.linearStart(), 150F);
		s.set(FogCustomizer.DEFAULT.linearEnd(), 100F);
		FogPlan.Distances d = air(s);
		assertEquals(VIEW * 1.5F, d.environmentalStart, 1e-3F);
		assertEquals(d.environmentalStart, d.environmentalEnd, "drawn the same, and Sodium culls no nearer than the fog");
		assertSane(d);
	}

	@Test
	void theSkyAndCloudsPassIgnoresNoFogAndTheMultiplier() {
		FogPlan.Distances plain = vanilla().set(VANILLA_RD_START, VIEW);
		FogPlan.adjust(plain, FogPlan.Medium.AIR, false, null, VIEW, settings(), true);
		for (int percent : new int[] {100, 200, 300}) {
			for (boolean noFog : new boolean[] {false, true}) {
				ModuleState s = settings();
				s.set(FogCustomizer.DISTANCE, percent);
				s.set(FogCustomizer.NO_FOG, noFog);
				FogPlan.Distances sky = vanilla().set(VANILLA_RD_START, VIEW);
				FogPlan.adjust(sky, FogPlan.Medium.AIR, false, null, VIEW, s, true);
				String what = percent + "% noFog=" + noFog;
				assertEquals(plain.environmentalStart, sky.environmentalStart, what);
				assertEquals(plain.environmentalEnd, sky.environmentalEnd, what);
				assertEquals(plain.renderDistanceStart, sky.renderDistanceStart, what);
				assertEquals(plain.renderDistanceEnd, sky.renderDistanceEnd, what);
				assertSane(sky);
			}
		}
		assertEquals(VIEW * 0.25F, plain.environmentalStart, 1e-3F, "the config's fog, as 1.21.1's sky always had it");
	}

	@Test
	void togglingNoFogLeavesNothingBehind() {
		FogPlan.Distances before = air(settings());
		ModuleState s = settings();
		for (int i = 0; i < 4; i++) {
			s.set(FogCustomizer.NO_FOG, i % 2 == 0);
			air(s);
		}
		s.set(FogCustomizer.NO_FOG, false);
		FogPlan.Distances after = air(s);
		assertEquals(before.environmentalStart, after.environmentalStart);
		assertEquals(before.environmentalEnd, after.environmentalEnd);
		assertEquals(before.renderDistanceStart, after.renderDistanceStart);
		assertEquals(before.renderDistanceEnd, after.renderDistanceEnd);
		assertEquals(before.skyEnd, after.skyEnd);
	}

	@Test
	void underWater() {
		ModuleState s = settings();
		s.set(FogCustomizer.NO_FOG, true);
		s.set(FogCustomizer.DISTANCE, 300);
		FogPlan.Distances d = vanilla().set(-8, 96);
		FogPlan.adjust(d, FogPlan.Medium.WATER, false, null, VIEW, s, false);
		// The Water config: Exponential, density 2: 95% at 3 / k, k = 2 / (0.3 view). No fog and the multiplier are for the open.
		assertEquals(0, d.environmentalStart);
		assertEquals(3 * 0.3F * VIEW / 2, d.environmentalEnd, 1e-2F);
		assertSane(d);

		s.set(FogCustomizer.CLEAR_WATER, true);
		FogPlan.Distances clear = vanilla().set(-8, 96);
		FogPlan.adjust(clear, FogPlan.Medium.WATER, false, null, VIEW, s, false);
		assertEquals(32, clear.environmentalStart);
		assertEquals(256, clear.environmentalEnd);
		assertEquals(256, clear.skyEnd, "the sky as far as the clear water");
		assertEquals(256, clear.cloudEnd);
		assertSane(clear);
	}

	@Test
	void lavaAndBlindnessKeepMinecraftsFog() {
		ModuleState s = settings();
		FogPlan.Distances lava = vanilla().set(0.25F, 1);
		FogPlan.adjust(lava, FogPlan.Medium.LAVA, false, null, VIEW, s, false);
		assertEquals(0.25F, lava.environmentalStart);
		assertEquals(1, lava.environmentalEnd);
		s.set(FogCustomizer.CLEAR_LAVA, true);
		FogPlan.adjust(lava, FogPlan.Medium.LAVA, false, null, VIEW, s, false);
		assertEquals(0, lava.environmentalStart);
		assertEquals(12, lava.environmentalEnd);

		FogPlan.Distances blind = vanilla().set(5, 8);
		FogPlan.adjust(blind, FogPlan.Medium.AIR, true, null, VIEW, settings(), false);
		assertEquals(5, blind.environmentalStart, "no config under Blindness");
		assertEquals(8, blind.environmentalEnd);

		// No fog and the multiplier never lift Blindness's or Darkness's fog.
		ModuleState clear = settings();
		clear.set(FogCustomizer.NO_FOG, true);
		FogPlan.Distances noFog = vanilla().set(5, 8);
		FogPlan.adjust(noFog, FogPlan.Medium.AIR, true, null, VIEW, clear, false);
		assertEquals(5, noFog.environmentalStart, "No fog doesn't lift Blindness");
		assertEquals(8, noFog.environmentalEnd);
		ModuleState far = settings();
		far.set(FogCustomizer.DISTANCE, 300);
		FogPlan.Distances further = vanilla().set(5, 8);
		FogPlan.adjust(further, FogPlan.Medium.AIR, true, null, VIEW, far, false);
		assertEquals(5, further.environmentalStart, "the multiplier doesn't stretch Blindness");
		assertEquals(8, further.environmentalEnd);
	}

	@Test
	void theNetherAndNamedDimensionsUseTheirConfigs() {
		ModuleState s = settings();
		// The Nether's config is off by default: Minecraft's fog there, with the sky's end at the render distance.
		FogPlan.Distances nether = vanilla();
		FogPlan.adjust(nether, FogPlan.Medium.AIR, false, "minecraft:the_nether", VIEW, s, false);
		assertEquals(VANILLA_ENV_END, nether.environmentalEnd);
		assertEquals(VIEW, nether.skyEnd);
		s.set(FogCustomizer.DIMENSION_1_ID, "minecraft:the_end");
		s.set(FogCustomizer.DIMENSION_1.linearEnd(), 50F);
		FogPlan.Distances end = vanilla();
		FogPlan.adjust(end, FogPlan.Medium.AIR, false, "minecraft:the_end", VIEW, s, false);
		assertEquals(VIEW * 0.5F, end.environmentalEnd, 1e-3F);
	}

	@Test
	void theRenderDistanceHasAFloor() {
		assertEquals(32, FogPlan.view(0));
		assertEquals(32, FogPlan.view(2));
		assertEquals(192, FogPlan.view(12));
	}
}
