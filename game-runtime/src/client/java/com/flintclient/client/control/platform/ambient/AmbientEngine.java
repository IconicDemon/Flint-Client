package com.flintclient.client.control.platform.ambient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

/**
 * One sound engine ("basic"): its scan settings and its parts (dimensions, regions, sound collections, sound categories,
 * block groups, features), read from {@code assets/flintclient/ambientsounds/<engine>/}, so a resource pack can add to or
 * replace any of them as with the mod. Ticks the regions in and out and the sounds' fades. Adapted from AmbientSounds'
 * {@code AmbientEngine} (LGPL-3.0, CreativeMD).
 */
final class AmbientEngine {
	static final String NAMESPACE = "flintclient";
	static final String ROOT = "ambientsounds";
	static final Gson GSON = new GsonBuilder()
		.registerTypeAdapter(Identifier.class, (JsonDeserializer<Identifier>) (json, type, context) -> Identifier.parse(json.getAsString()))
		.registerTypeAdapter(Identifier.class, (JsonSerializer<Identifier>) (id, type, context) -> new JsonPrimitive(id.toString())).create();

	transient LinkedHashMap<String, AmbientDimension> dimensions;
	transient final LinkedHashMap<String, AmbientRegion> allRegions = new LinkedHashMap<>();
	transient LinkedHashMap<String, AmbientRegion> generalRegions;
	transient final List<AmbientRegion> activeRegions = new ArrayList<>();
	transient final LinkedHashMap<String, AmbientSound> allSounds = new LinkedHashMap<>();
	transient LinkedHashMap<String, AmbientSoundCategory> soundCategories;
	transient List<AmbientSoundCategory> sortedSoundCategories;
	transient LinkedHashMap<String, AmbientBlocks.Group> blockGroups;
	transient LinkedHashMap<String, Collection> soundCollections;
	transient LinkedHashMap<String, AmbientBlocks.Feature> features;
	transient AmbientSoundEngine soundEngine;
	transient final List<String> silentDimensions = new ArrayList<>();
	transient AmbientDimension silentDim;
	transient List<Double> airPocketDistanceFactor;
	transient int maxAirPocketCount;
	transient AmbientBlocks.Group considerSolid;
	transient List<TagKey<Fluid>> considerWater;
	transient double squaredBiomeDistance;
	/** The fades the settings ask for (the engine file's fade-volume and fade-pitch are their defaults). */
	transient double fadeVolumeSetting;
	transient double fadePitchSetting;

	public String name;
	public String version;
	@SerializedName("environment-tick-time")
	public int environmentTickTime = 40;
	@SerializedName("sound-tick-time")
	public int soundTickTime = 4;
	@SerializedName("block-scan-distance")
	public int blockScanDistance = 40;
	@SerializedName("average-height-scan-distance")
	public int averageHeightScanDistance = 2;
	@SerializedName("average-height-scan-count")
	public int averageHeightScanCount = 5;
	@SerializedName("biome-scan-distance")
	public int biomeScanDistance = 5;
	@SerializedName("biome-scan-count")
	public int biomeScanCount = 3;
	@SerializedName("air-pocket-count")
	public int airPocketCount = 50000;
	@SerializedName("air-pocket-groups")
	public AirPocketGroup[] airPocketGroups = new AirPocketGroup[0];
	@SerializedName("air-distance")
	public int airDistance = 25;
	@SerializedName("air-sky-distance")
	public int airSkyDistance = 13;
	@SerializedName("air-sky-weight")
	public double airSkyWeight = 50;
	@SerializedName("air-min")
	public double airMin = 0.1;
	@SerializedName("air-max")
	public double airMax = 0.5;
	@SerializedName("sky-distance")
	public double skyDistance = 20;
	@SerializedName("sky-min-count")
	public int skyMinCount = 10;
	@SerializedName("sky-max-count")
	public int skyMaxCount = 100;
	public String[] solids = new String[0];
	public String[] water = new String[0];
	@SerializedName("biome-types")
	public String[] biomeTypes = new String[0];
	@SerializedName("default-biome-type")
	public String defaultBiomeType;
	@SerializedName("fade-volume")
	public Double fadeVolume = 0.005;
	@SerializedName("fade-pitch")
	public Double fadePitch = 0.005;

	static final class AirPocketGroup {
		public int distance;
		public double weight;
	}

	static final class Collection {
		public AmbientStackType stack = AmbientStackType.overwrite;
		public AmbientSound[] sounds;
	}

	double fadeVolume() {
		return fadeVolumeSetting;
	}

	double fadePitch() {
		return fadePitchSetting;
	}

	@FunctionalInterface
	private interface Loader<T> {
		void load(T value, String name, JsonElement json);
	}

	/** Loads engine {@code name} from the resources; sound events the game doesn't have are left out of each sound. */
	static AmbientEngine load(ResourceManager manager, SoundManager sounds, String name) throws IOException {
		AmbientEngine engine;
		try (Reader reader = reader(manager.getResourceOrThrow(Identifier.fromNamespaceAndPath(NAMESPACE, ROOT + "/" + name + "/engine.json")))) {
			engine = GSON.fromJson(JsonParser.parseReader(reader), AmbientEngine.class);
		}
		if (!name.equals(engine.name)) {
			throw new IOException("Invalid engine name");
		}
		AmbientEngine e = engine;
		e.dimensions = loadMultiple(manager, name + "/dimensions", AmbientDimension.class, d -> d.stack, (dimension, dimensionName, json) -> {
			dimension.name = dimensionName;
			dimension.load(e, GSON, json);
			if (dimension.loadedRegions != null) {
				for (AmbientRegion region : dimension.loadedRegions.values()) {
					if (region.name != null && !region.name.isEmpty()) {
						e.addRegion(region);
					} else {
						AmbientSounds.LOGGER.error("Ambient Sounds: found an invalid region in '{}'", dimension.name);
					}
				}
			}
		});
		e.generalRegions = loadMultiple(manager, name + "/regions", AmbientRegion.class, r -> r.stack, (region, regionName, json) -> {
			region.name = regionName;
			region.load();
			e.addRegion(region);
		});
		e.blockGroups = new LinkedHashMap<>();
		String groupPath = ROOT + "/" + name + "/blockgroups";
		for (Map.Entry<Identifier, List<Resource>> file : manager.listResourceStacks(groupPath, id -> id.getNamespace().equals(NAMESPACE)
			&& id.getPath().endsWith(".json")).entrySet()) {
			AmbientBlocks.Group group = new AmbientBlocks.Group();
			String groupName = file.getKey().getPath().substring(groupPath.length() + 1).replace(".json", "");
			for (Resource resource : file.getValue()) {
				try (Reader reader = reader(resource)) {
					group.add(GSON.fromJson(JsonParser.parseReader(reader), String[].class));
				} catch (RuntimeException | LinkageError ex) {
					AmbientSounds.LOGGER.error("Ambient Sounds: failed to load block group {}", file.getKey(), ex);
				}
			}
			e.blockGroups.put(groupName, group);
		}
		e.soundCollections = loadMultiple(manager, name + "/sound_collections", Collection.class, c -> c.stack, (c, n, json) -> {
		});
		e.soundCategories = loadMultiple(manager, name + "/sound_categories", AmbientSoundCategory.class, c -> c.stack, (c, n, json) -> c.name = n);
		e.features = loadMultiple(manager, name + "/features", AmbientBlocks.Feature.class, f -> f.stack, (f, n, json) -> f.name = n);
		e.silentDim = new AmbientDimension();
		e.silentDim.name = "silent";
		e.silentDim.volumeSetting = 0;
		e.silentDim.mute = true;
		e.dropMissingSounds(sounds);
		e.init();
		AmbientSounds.LOGGER.info(
			"Ambient Sounds: loaded engine '{}' v{}: {} dimension(s), {} features, {} block groups, {} sound collections, {} regions, {} sounds, {} sound categories",
			e.name, e.version, e.dimensions.size(), e.features.size(), e.blockGroups.size(), e.soundCollections.size(), e.allRegions.size(),
			e.allSounds.size(), e.soundCategories.size());
		return e;
	}

	private static Reader reader(Resource resource) throws IOException {
		InputStream input = resource.open();
		return new InputStreamReader(input, StandardCharsets.UTF_8);
	}

	private static <T> LinkedHashMap<String, T> loadMultiple(ResourceManager manager, String folder, Class<T> type,
		Function<T, AmbientStackType> stackOf, Loader<T> loader) {
		LinkedHashMap<String, T> map = new LinkedHashMap<>();
		String path = ROOT + "/" + folder;
		for (Map.Entry<Identifier, List<Resource>> file : manager.listResourceStacks(path, id -> id.getNamespace().equals(NAMESPACE)
			&& id.getPath().endsWith(".json")).entrySet()) {
			T base = null;
			String name = file.getKey().getPath().substring(path.length() + 1).replace(".json", "");
			for (Resource resource : file.getValue()) {
				try (Reader reader = reader(resource)) {
					JsonElement json = JsonParser.parseReader(reader);
					T next = GSON.fromJson(json, type);
					if (base == null) {
						base = next;
					} else {
						AmbientStackType stack = stackOf.apply(next);
						if (stack == AmbientStackType.overwrite) {
							base = next;
						} else {
							applyStackType(base, next, stack);
						}
					}
					loader.load(base, name, json);
					map.put(name, base);
				} catch (IOException | RuntimeException ex) {
					AmbientSounds.LOGGER.error("Ambient Sounds: failed to load {} {}", type.getSimpleName(), file.getKey(), ex);
				}
			}
		}
		return map;
	}

	private static <T> void applyStackType(T base, T next, AmbientStackType stack) {
		for (Field field : base.getClass().getFields()) {
			if (!Modifier.isTransient(field.getModifiers()) && !Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
				try {
					stack.apply(base, field, next);
				} catch (IllegalAccessException | IllegalArgumentException ex) {
					// as the mod: a field it can't merge stays as it was
				}
			}
		}
	}

	/** Each sound keeps only the sound events this Minecraft has (the engine names some newer ones); a sound left with none is dropped. */
	private void dropMissingSounds(SoundManager manager) {
		for (AmbientRegion region : allRegions.values()) {
			if (region.loadedSounds != null) {
				region.loadedSounds.values().removeIf(sound -> !keepKnown(sound, manager));
			}
		}
		for (Collection collection : soundCollections.values()) {
			if (collection.sounds != null) {
				List<AmbientSound> kept = new ArrayList<>();
				for (AmbientSound sound : collection.sounds) {
					if (keepKnown(sound, manager)) {
						kept.add(sound);
					}
				}
				collection.sounds = kept.toArray(new AmbientSound[0]);
			}
		}
		allSounds.values().removeIf(sound -> sound.files == null || sound.files.length == 0);
	}

	private static boolean keepKnown(AmbientSound sound, SoundManager manager) {
		if (sound.files == null) {
			return false;
		}
		List<Identifier> known = new ArrayList<>();
		for (Identifier file : sound.files) {
			if (manager.getSoundEvent(file) != null) {
				known.add(file);
			}
		}
		if (known.size() != sound.files.length) {
			sound.files = known.toArray(new Identifier[0]);
			sound.chances = null;
		}
		return !known.isEmpty();
	}

	@Nullable AmbientRegion getRegion(String name) {
		return allRegions.get(name);
	}

	private void addRegion(AmbientRegion region) {
		String key = (region.dimension != null ? region.dimension.name + "." : "") + region.name;
		allRegions.put(key, region);
		region.volumeSetting = 1;
		if (region.loadedSounds != null) {
			for (AmbientSound sound : region.loadedSounds.values()) {
				allSounds.put(key + "." + sound.name, sound);
				sound.fullName = key + "." + sound.name;
				sound.volumeSetting = 1;
			}
		}
	}

	AmbientDimension getDimension(Level level) {
		String id = AmbientCompat.id(level.dimension()).toString();
		for (String silent : silentDimensions) {
			if (id.matches(".*" + silent.toLowerCase(java.util.Locale.ROOT).replace("*", ".*").replace("?", "\\?") + ".*")) {
				return silentDim;
			}
		}
		for (AmbientDimension dimension : dimensions.values()) {
			if (dimension.is(level)) {
				return dimension;
			}
		}
		return silentDim;
	}

	void stopEngine() {
		for (AmbientRegion region : activeRegions) {
			region.deactivate();
		}
		activeRegions.clear();
	}

	void consumeSoundCollections(String[] groups, Consumer<AmbientSound> consumer) {
		for (String group : groups) {
			Collection collection = soundCollections.get(group);
			if (collection != null && collection.sounds != null) {
				for (AmbientSound sound : collection.sounds) {
					consumer.accept(sound);
				}
			}
		}
	}

	/** The mod's air pocket volume of radius {@code r} (it only ever weights the outermost shell 1: kept as is). */
	int airPocketVolume(int r) {
		int res = 0;
		for (int i = r; r > 0; r--) {
			int f = i == r ? 1 : i == r - 1 ? 4 : i == r - 2 ? 7 : 8;
			res = (int) (res + f * r * (r + 1) * 0.5);
		}
		return res;
	}

	private void init() {
		airPocketDistanceFactor = new ArrayList<>();
		for (AirPocketGroup group : airPocketGroups) {
			for (int i = 0; i < group.distance; i++) {
				airPocketDistanceFactor.add(group.weight);
			}
		}
		maxAirPocketCount = airPocketVolume(airDistance);
		for (Map.Entry<String, Collection> group : soundCollections.entrySet()) {
			if (group.getValue().sounds != null) {
				for (AmbientSound sound : group.getValue().sounds) {
					sound.name = group.getKey() + "." + sound.name;
					allSounds.put(sound.name, sound);
					sound.init(this);
				}
			}
		}
		for (AmbientDimension dimension : dimensions.values()) {
			dimension.init(this);
		}
		for (AmbientRegion region : allRegions.values()) {
			region.init(this);
		}
		for (AmbientSoundCategory category : soundCategories.values()) {
			category.init(this);
		}
		sortedSoundCategories = new ArrayList<>();
		HashSet<String> unsorted = new HashSet<>(soundCategories.keySet());
		for (AmbientSoundCategory category : soundCategories.values()) {
			if (category.parent == null || category.parent.isBlank()) {
				category.postInit(unsorted);
				sortedSoundCategories.add(category);
			} else if (category.parentCategory == null) {
				AmbientSounds.LOGGER.error("Ambient Sounds: sound category {} has no parent '{}'", category.name, category.parent);
			}
			if (unsorted.isEmpty()) {
				break;
			}
		}
		considerSolid = new AmbientBlocks.Group();
		if (solids != null) {
			considerSolid.add(solids);
		}
		considerWater = new ArrayList<>();
		if (water != null) {
			for (String fluid : water) {
				Identifier id = Identifier.tryParse(fluid);
				if (id != null) {
					considerWater.add(TagKey.create(Registries.FLUID, id));
				}
			}
		}
		squaredBiomeDistance = Math.pow(biomeScanCount * biomeScanDistance * 2, 2);
		fadeVolumeSetting = fadeVolume;
		fadePitchSetting = fadePitch;
		onClientLoad();
	}

	void onClientLoad() {
		blockGroups.values().forEach(AmbientBlocks.Group::onClientLoad);
		considerSolid.onClientLoad();
	}

	double airWeightFactor(int distance) {
		return distance >= airPocketDistanceFactor.size() ? 0 : airPocketDistanceFactor.get(distance);
	}

	void tick(AmbientEnvironment env) {
		for (AmbientSoundCategory category : sortedSoundCategories) {
			category.tick(env, null);
		}
		if (env.dimension.loadedRegions != null) {
			for (AmbientRegion region : env.dimension.loadedRegions.values()) {
				tickRegion(region, env);
			}
		}
		for (AmbientRegion region : generalRegions.values()) {
			tickRegion(region, env);
		}
	}

	private void tickRegion(AmbientRegion region, AmbientEnvironment env) {
		if (region.tick(env)) {
			if (!region.isActive()) {
				region.activate();
				activeRegions.add(region);
			}
		} else if (region.isActive()) {
			region.deactivate();
			activeRegions.remove(region);
		}
	}

	void fastTick(AmbientEnvironment env) {
		if (soundEngine == null) {
			return;
		}
		soundEngine.tick();
		Iterator<AmbientRegion> it = activeRegions.iterator();
		while (it.hasNext()) {
			AmbientRegion region = it.next();
			if (!region.fastTick(env)) {
				region.deactivate();
				it.remove();
			}
		}
	}

	void changeDimension(AmbientEnvironment env, AmbientDimension next) {
		if (env.dimension != null && env.dimension.loadedRegions != null) {
			for (AmbientRegion region : env.dimension.loadedRegions.values()) {
				if (region.isActive()) {
					region.deactivate();
					activeRegions.remove(region);
				}
			}
		}
	}

	@Nullable AmbientSoundCategory getSoundCategory(@Nullable String name) {
		return name == null ? null : soundCategories.get(name);
	}
}
