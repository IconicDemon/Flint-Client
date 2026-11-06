package com.flintclient.client.control.platform.ambient;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.annotations.SerializedName;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.world.level.Level;

/**
 * A dimension of the engine (matched by its id), with its own regions and rules: whether it's night or raining there,
 * a fixed ground height, how much its biomes count. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
final class AmbientDimension {
	transient double volumeSetting = 1;
	transient LinkedHashMap<String, AmbientRegion> loadedRegions;
	transient HashMap<String, AmbientCondition> biomeTypeSelectors = new HashMap<>();
	transient String name;
	transient Pattern[] dimensionPatterns;
	transient Pattern[] badDimensionPatterns;
	public boolean mute;
	@SerializedName("biome-selector")
	public AmbientCondition biomeSelector;
	public Boolean night;
	public Boolean rain;
	public Boolean storm;
	@SerializedName("dimension-names")
	public String[] dimensionNames;
	@SerializedName("bad-dimension-names")
	public String[] badDimensionNames;
	@SerializedName("average-height")
	public Integer averageHeight;
	public AmbientRegion[] regions;
	public AmbientStackType stack = AmbientStackType.overwrite;

	void load(AmbientEngine engine, Gson gson, JsonElement element) {
		if (regions != null) {
			loadedRegions = new LinkedHashMap<>();
			for (AmbientRegion region : regions) {
				region.dimension = this;
				loadedRegions.put(region.name, region);
				region.load();
			}
		}
		if (element.isJsonObject()) {
			for (String type : engine.biomeTypes) {
				JsonElement selector = element.getAsJsonObject().get(type + "-selector");
				if (selector != null) {
					biomeTypeSelectors.put(type, gson.fromJson(selector, AmbientCondition.class));
				}
			}
		}
	}

	void init(AmbientEngine engine) {
		if (biomeSelector != null) {
			biomeSelector.init(engine);
		}
		for (AmbientCondition condition : biomeTypeSelectors.values()) {
			condition.init(engine);
		}
		badDimensionPatterns = patterns(badDimensionNames);
		dimensionPatterns = patterns(dimensionNames);
	}

	static Pattern[] patterns(String[] names) {
		if (names == null) {
			return null;
		}
		Pattern[] out = new Pattern[names.length];
		for (int i = 0; i < names.length; i++) {
			out[i] = Pattern.compile(".*" + names[i].toLowerCase(Locale.ROOT).replace("*", ".*").replace("?", "\\?") + ".*");
		}
		return out;
	}

	boolean is(Level level) {
		String id = AmbientCompat.id(level.dimension()).toString();
		if (badDimensionPatterns != null) {
			for (Pattern pattern : badDimensionPatterns) {
				if (pattern.matcher(id).matches()) {
					return false;
				}
			}
		}
		if (dimensionPatterns != null) {
			for (Pattern pattern : dimensionPatterns) {
				if (pattern.matcher(id).matches()) {
					return true;
				}
			}
		}
		return dimensionNames == null;
	}

	void manipulateEnvironment(AmbientEnvironment env) {
		env.muted = mute;
		if (night != null) {
			env.night = night;
		}
		if (rain != null) {
			env.raining = rain;
		}
		if (storm != null) {
			env.thundering = storm;
		}
		if (biomeSelector != null) {
			AmbientSelection selection = biomeSelector.value(env);
			env.biomeVolume = selection != null ? selection : AmbientVolume.SILENT;
		}
		env.biomeTypeVolumes.clear();
		for (Map.Entry<String, AmbientCondition> entry : biomeTypeSelectors.entrySet()) {
			AmbientSelection selection = entry.getValue().value(env);
			env.biomeTypeVolumes.put(entry.getKey(), selection != null ? selection : AmbientVolume.SILENT);
		}
	}

	@Override
	public String toString() {
		return name;
	}
}
