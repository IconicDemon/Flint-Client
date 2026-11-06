/*
 * Custom Fog's fog configs are adapted from Custom Fog by Setadokalo (MIT License; text in META-INF/licenses/MIT-CustomFog.txt).
 * Changed for Flint: its configs are Flint settings, and the fog they give is written into each Minecraft line's own fog.
 */
package com.flintclient.client.control.feature;

import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.FogCustomizer;
import org.jspecify.annotations.Nullable;

/**
 * Fog Customizer's fog, worked out once for every Minecraft line. Pure Java: each line's fog hook copies the fog Minecraft
 * just computed into a {@link Distances}, runs {@link #adjust} and copies it back (26.x and 1.21.10+ into their FogData,
 * 1.21.1 into its one fog start and end).
 *
 * <p>Custom Fog's part: the config for where the camera is (water, powder snow, else the dimension's own or Default) sets
 * the fog when enabled: Linear from a start to an end share of the render distance, Exponential and Exponential^2 by
 * density (drawn between where they reach 5% and 95%, as Minecraft's fog is a straight ramp), or None. Lava and Blindness
 * keep Minecraft's fog. In the open, No fog and the distance multiplier then act on it; Clear water and Clear lava under
 * water and in lava.
 *
 * <p>Every distance it gives is finite and at most {@link #limit} blocks: No fog, None, a density of 0 and the largest
 * multiplier push the fog past everything Minecraft draws (its far plane is 4 render distances away), never to a huge
 * number or infinity, which the fog shaders, Sodium's fog culling and shader packs can't take. The sky's fog ends at the
 * render distance whatever No fog and the multiplier say, so the sky's gradient stays put while they change.
 */
public final class FogPlan {
	/** Where the camera is, as far as the fog goes. */
	public enum Medium {
		AIR, WATER, POWDER_SNOW, LAVA, OTHER
	}

	/** Fog distances in blocks, as Minecraft 26.x's FogData names them. */
	public static final class Distances {
		public float environmentalStart;
		public float environmentalEnd;
		public float renderDistanceStart;
		public float renderDistanceEnd;
		public float skyEnd;
		public float cloudEnd;

		/** Every distance from one start and end (1.21.1's fog). */
		public Distances set(float start, float end) {
			environmentalStart = start;
			environmentalEnd = end;
			renderDistanceStart = start;
			renderDistanceEnd = end;
			skyEnd = end;
			cloudEnd = end;
			return this;
		}
	}

	/** How many render distances away No fog's start is: Minecraft's far plane is at 4, a wide view's corners reach past it. */
	static final float CLEAR_RENDER_DISTANCES = 16;
	/** No fog's start in blocks at least: past the height of the world at a small render distance. */
	static final float CLEAR_MIN = 4096;

	private FogPlan() {
	}

	/** The render distance in blocks the fog is measured against: {@code chunks} chunks, at least 32 blocks. */
	public static float view(int chunks) {
		return Math.max(chunks * 16, 32);
	}

	/** Where No fog starts the fog: past anything Minecraft draws at render distance {@code view}, yet a small number for a float. */
	public static float clearStart(float view) {
		return Math.max(view * CLEAR_RENDER_DISTANCES, CLEAR_MIN);
	}

	/** The farthest distance the plan gives (No fog's end), and the most any fog distance can be. */
	public static float limit(float view) {
		return clearStart(view) * 2;
	}

	/**
	 * Adjusts {@code fog}, Minecraft's fog for this frame, as Fog Customizer's settings {@code s} say.
	 *
	 * @param blind whether the camera's entity has Blindness or Darkness (its fog is left to Minecraft, No fog included)
	 * @param dimension the dimension's id ("minecraft:the_nether"), for its config; null: Default
	 * @param view the render distance in blocks ({@link #view})
	 * @param skyAndClouds 1.21.1's sky and clouds, which share the terrain's one fog start and end: No fog and the distance
	 *     multiplier, which are about seeing the terrain further, leave them as they are (26.x keeps the sky's and the clouds'
	 *     own fog ends)
	 */
	public static void adjust(Distances fog, Medium medium, boolean blind, @Nullable String dimension, float view, ModuleState s,
		boolean skyAndClouds) {
		if (!blind && (medium == Medium.AIR || medium == Medium.WATER || medium == Medium.POWDER_SNOW)) {
			FogCustomizer.Config config = medium == Medium.WATER ? FogCustomizer.WATER
				: medium == Medium.POWDER_SNOW ? FogCustomizer.SNOW : config(s, dimension);
			fog.skyEnd = view;
			if (s.bool(config.enabled())) {
				configured(fog, s, config, view);
			}
		}
		switch (medium) {
			case WATER -> {
				if (s.bool(FogCustomizer.CLEAR_WATER)) {
					fog.environmentalStart = Math.max(fog.environmentalStart, 32);
					fog.environmentalEnd = Math.max(fog.environmentalEnd, 256);
					fog.skyEnd = fog.environmentalEnd;
					fog.cloudEnd = fog.environmentalEnd;
				}
			}
			case LAVA -> {
				if (s.bool(FogCustomizer.CLEAR_LAVA)) {
					fog.environmentalStart = 0;
					fog.environmentalEnd = Math.max(fog.environmentalEnd, 12);
				}
			}
			case AIR -> {
				// Blindness and Darkness keep Minecraft's fog: No fog and the multiplier never lift them (that would be an unfair
				// advantage on servers, which anti-cheats look for).
				if (skyAndClouds || blind) {
					break;
				}
				if (s.bool(FogCustomizer.NO_FOG)) {
					fog.environmentalStart = clearStart(view);
					fog.environmentalEnd = limit(view);
					fog.renderDistanceStart = clearStart(view);
					fog.renderDistanceEnd = limit(view);
				} else {
					float k = s.integer(FogCustomizer.DISTANCE) / 100.0F;
					fog.environmentalStart *= k;
					fog.environmentalEnd *= k;
					fog.renderDistanceStart *= k;
					fog.renderDistanceEnd *= k;
				}
			}
			default -> {
			}
		}
		settle(fog, view);
	}

	/** Whether the fog colour is Fog Customizer's own here (in the open, with Override fog color on). */
	public static boolean overridesColor(Medium medium, ModuleState s) {
		return medium == Medium.AIR && s.bool(FogCustomizer.OVERRIDE_COLOR);
	}

	/** The config of the dimension {@code id} ("minecraft:the_nether"), or Default. */
	static FogCustomizer.Config config(ModuleState s, @Nullable String id) {
		if (id == null) {
			return FogCustomizer.DEFAULT;
		}
		if (id.equals(s.text(FogCustomizer.DIMENSION_1_ID).strip())) {
			return FogCustomizer.DIMENSION_1;
		}
		if (id.equals(s.text(FogCustomizer.DIMENSION_2_ID).strip())) {
			return FogCustomizer.DIMENSION_2;
		}
		return "minecraft:the_nether".equals(id) ? FogCustomizer.NETHER : FogCustomizer.DEFAULT;
	}

	/** Custom Fog's fog for {@code config}: the same start and end for both of Minecraft's fogs. */
	private static void configured(Distances fog, ModuleState s, FogCustomizer.Config config, float view) {
		float start;
		float end;
		switch (s.option(config.type())) {
			case 0 -> {
				start = view * s.decimal(config.linearStart()) / 100.0F;
				end = view * s.decimal(config.linearEnd()) / 100.0F;
			}
			case 1 -> {
				// fog = 1 - exp(-d * density / (0.3 view)): 95% at 3 / k.
				double k = s.decimal(config.exp()) / (0.3 * view);
				start = 0;
				end = k <= 0 ? limit(view) : (float) (3 / k);
			}
			case 2 -> {
				// fog = 1 - exp(-d^2 * density / (50 view)): 5% at sqrt(0.0513 / k), 95% at sqrt(3 / k).
				double k = s.decimal(config.exp2()) / (50.0 * view);
				start = k <= 0 ? clearStart(view) : (float) Math.sqrt(0.0513 / k);
				end = k <= 0 ? limit(view) : (float) Math.sqrt(3 / k);
			}
			default -> {
				start = clearStart(view);
				end = limit(view);
			}
		}
		fog.environmentalStart = start;
		fog.environmentalEnd = end;
		fog.renderDistanceStart = start;
		fog.renderDistanceEnd = end;
	}

	/**
	 * Keeps every distance finite and within {@link #limit}, and each fog's end at or past its start (the shaders draw an
	 * end before the start as the start, but Sodium culls the terrain past the end as if the fog were thick there).
	 */
	static void settle(Distances fog, float view) {
		float limit = limit(view);
		fog.environmentalStart = bound(fog.environmentalStart, limit);
		fog.environmentalEnd = Math.max(bound(fog.environmentalEnd, limit), fog.environmentalStart);
		fog.renderDistanceStart = bound(fog.renderDistanceStart, limit);
		fog.renderDistanceEnd = Math.max(bound(fog.renderDistanceEnd, limit), fog.renderDistanceStart);
		fog.skyEnd = bound(fog.skyEnd, limit);
		fog.cloudEnd = bound(fog.cloudEnd, limit);
	}

	/** {@code value} within ±{@code limit}; not a number counts as no fog ({@code limit}). */
	static float bound(float value, float limit) {
		return Float.isNaN(value) ? limit : Math.max(-limit, Math.min(value, limit));
	}
}
