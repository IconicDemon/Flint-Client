package com.flintclient.client.control.platform.ambient;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * When something is heard, and how loud: time of day, weather, biomes, height, light, how much air and sky is around,
 * nearby block features, entities, other regions, variants. Every rule that is set must hold; the volume is the product
 * of their fades. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
class AmbientCondition extends AmbientSoundProperties {
	public Boolean always;
	public double volume = 1.0;
	@SerializedName("night")
	public double nightVolume = 1.0;
	@SerializedName("day")
	public double dayVolume = 1.0;
	public AmbientTime time;
	@SerializedName("biome-type")
	public String biomeType;
	public String[] biomes;
	transient BiomeCondition[] biomesCompiled;
	@SerializedName("bad-biomes")
	public String[] badBiomes;
	transient BiomeCondition[] badBiomesCompiled;
	public Boolean raining;
	@SerializedName("overall-raining")
	public Boolean overallRaining;
	public Boolean snowing;
	public Boolean storming;
	public MinMaxFade underwater;
	@SerializedName("relative-height")
	public MinMaxFadeSpecial relativeHeight;
	@SerializedName("absolute-height")
	public MinMaxFade absoluteHeight;
	@SerializedName("min-height-relative")
	public MinMaxFade minHeightRelative;
	@SerializedName("max-height-relative")
	public MinMaxFade maxHeightRelative;
	@SerializedName("light")
	public MinMaxFade light;
	@SerializedName("block-light")
	public MinMaxFade blockLight;
	@SerializedName("sky-light")
	public MinMaxFade skyLight;
	public MinMaxFade air;
	public MinMaxFade temperature;
	public MinMaxFade sky;
	public String[] features;
	@SerializedName("bad-features")
	public String[] badFeatures;
	public AmbientCondition[] variants;
	public String[] regions;
	transient List<AmbientRegion> regionList;
	@SerializedName("bad-regions")
	public String[] badRegions;
	transient List<AmbientRegion> badRegionList;
	public AmbientEntityCondition entity;

	@Nullable String regionName() {
		return null;
	}

	@Override
	void init(AmbientEngine engine) {
		super.init(engine);
		volume = Mth.clamp(volume, 0.0, 1.0);
		nightVolume = Mth.clamp(nightVolume, 0.0, 1.0);
		dayVolume = Mth.clamp(dayVolume, 0.0, 1.0);
		if (variants != null) {
			for (AmbientCondition variant : variants) {
				variant.init(engine);
			}
		}
		biomesCompiled = BiomeCondition.of(biomes);
		badBiomesCompiled = BiomeCondition.of(badBiomes);
		if (regions != null) {
			regionList = new ArrayList<>();
			for (String name : regions) {
				AmbientRegion region = engine.getRegion(name);
				if (region != null && !name.equals(regionName())) {
					regionList.add(region);
				}
			}
		}
		if (badRegions != null) {
			badRegionList = new ArrayList<>();
			for (String name : badRegions) {
				AmbientRegion region = engine.getRegion(name);
				if (region != null && !name.equals(regionName())) {
					badRegionList.add(region);
				}
			}
		}
		if (biomeType == null) {
			biomeType = engine.defaultBiomeType;
		}
		if (entity != null) {
			entity.init();
		}
	}

	@Nullable AmbientSelection value(AmbientEnvironment env) {
		if (env.muted) {
			return null;
		}
		if (always != null) {
			return always ? new AmbientSelection(this) : null;
		}
		if (volume <= 0 || (env.night ? nightVolume <= 0 : dayVolume <= 0)) {
			return null;
		}
		if (raining != null && raining != env.raining || overallRaining != null && overallRaining != env.isRainAudibleAtSurface()
			|| snowing != null && snowing != env.snowing || storming != null && env.thundering != storming
			|| badFeatures != null && env.terrain.airPocket.volume(badFeatures) > 0) {
			return null;
		}
		AmbientSelection selection = new AmbientSelection(this);
		selection.mulCondition(env.night ? nightVolume : dayVolume);
		if (time != null) {
			double value = time.value(env);
			if (value <= 0) {
				return null;
			}
			selection.mulCondition(value);
		}
		if (badRegionList != null) {
			for (AmbientRegion region : badRegionList) {
				if (region.isActive()) {
					return null;
				}
			}
		}
		if (regionList != null) {
			AmbientVolume highest = null;
			for (AmbientRegion region : regionList) {
				AmbientSelection sub = region.value(env);
				if (sub != null && (highest == null || sub.volume() > highest.volume())) {
					highest = sub;
				}
				if (highest != null && highest.volume() == 1.0) {
					break;
				}
			}
			if (highest == null) {
				return null;
			}
			selection.mulVolume(highest);
		}
		if (biomes != null || badBiomes != null) {
			AmbientVolume highest = null;
			for (BiomeEnvironment.Entry entry : env.biome) {
				if (biomes == null || entry.area().checkBiome(biomesCompiled)) {
					if (badBiomes != null && entry.area().checkBiome(badBiomesCompiled)) {
						return null;
					}
					if (biomes != null) {
						AmbientVolume volume = entry.volume().copy();
						volume.mulVolume(env.biomeTypeVolumes.getOrDefault(biomeType, AmbientVolume.MAX));
						if (highest == null || highest.volume() < volume.volume()) {
							highest = volume;
						}
						if (highest.volume() == 1.0) {
							break;
						}
					}
				}
			}
			if (highest == null && biomes != null) {
				return null;
			}
			if (highest != null) {
				selection.mulVolume(highest);
			}
		}
		if (overallRaining != null && overallRaining) {
			selection.mulCondition(env.rainSurfaceVolume);
		}
		if (!fade(selection, underwater, env.underwater) || relativeHeight != null && !mul(selection,
			relativeHeight.volume(env.relativeMinHeight, env.relativeHeight, env.relativeMaxHeight))
			|| !fade(selection, minHeightRelative, env.relativeMinHeight) || !fade(selection, maxHeightRelative, env.relativeMaxHeight)
			|| !fade(selection, absoluteHeight, env.absoluteHeight) || !fade(selection, light, env.terrain.airPocket.averageLight)
			|| !fade(selection, blockLight, env.terrain.airPocket.averageBlockLight)
			|| !fade(selection, skyLight, env.terrain.airPocket.averageSkyLight) || !fade(selection, air, env.terrain.airPocket.air)
			|| !fade(selection, sky, env.terrain.airPocket.sky)) {
			return null;
		}
		if (features != null && !mul(selection, env.terrain.airPocket.volume(features))) {
			return null;
		}
		if (!fade(selection, temperature, env.temperature)) {
			return null;
		}
		if (entity != null && !mul(selection, entity.value(env))) {
			return null;
		}
		if (variants != null) {
			AmbientSelection best = null;
			for (AmbientCondition condition : variants) {
				AmbientSelection sub = condition.value(env);
				if (sub != null && (best == null || best.volume() < sub.volume())) {
					best = sub;
				}
			}
			if (best == null) {
				return null;
			}
			selection.subSelection = best;
		}
		return selection;
	}

	/** Multiplies in {@code range}'s fade at {@code value}; false (the condition fails) when that is 0. True with no range. */
	private static boolean fade(AmbientSelection selection, @Nullable MinMaxFade range, double value) {
		return range == null || mul(selection, range.volume(value));
	}

	private static boolean mul(AmbientSelection selection, double volume) {
		if (volume <= 0) {
			return false;
		}
		selection.mulCondition(volume);
		return true;
	}

	/** A range: below min or above max fails. */
	static class MinMax {
		public Double min;
		public Double max;

		boolean is(double value) {
			return !(min != null && value < min) && (max == null || !(value > max));
		}

		double randomValue() {
			if (max == null) {
				return min == null ? 0 : min;
			}
			if (min == null) {
				min = 0.0;
			}
			return Math.random() * (max - min) + min;
		}
	}

	/** A range that fades in over {@code fade} from its ends. */
	static class MinMaxFade extends MinMax {
		public Double fade;

		double volume(double value) {
			if (!is(value)) {
				return 0;
			}
			if (fade == null) {
				return 1;
			}
			double volume = 1;
			if (min != null) {
				volume = Mth.clamp(Math.abs(value - min) / fade, 0.0, 1.0);
			}
			if (max != null) {
				volume = Math.min(volume, Mth.clamp(Math.abs(value - max) / fade, 0.0, 1.0));
			}
			return volume;
		}
	}

	/** A height range that also counts the lowest and highest ground around (relative height). */
	static class MinMaxFadeSpecial extends MinMaxFade {
		double volume(double min, double value, double max) {
			if (fade == null) {
				return is(value) ? 1 : 0;
			}
			double volume = volume(value);
			return volume == 1.0 ? volume : Math.max(volume(min), volume(max));
		}
	}
}
