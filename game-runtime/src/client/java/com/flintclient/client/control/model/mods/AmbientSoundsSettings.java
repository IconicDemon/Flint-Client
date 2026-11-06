package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import com.flintclient.client.control.model.TextSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ambient Sounds' settings: every option of AmbientSounds 6.3.11 (LGPL-3.0, by CreativeMD and AriaFreeze): its general
 * options, the engine's fades and silent dimensions, and a volume for each dimension, region, region sound and sound
 * category of the engine (Flint's "basic" engine, {@code assets/flintclient/ambientsounds/basic}, which keeps the
 * original's dimensions, regions and categories). The names below match that data; a test checks they stay in step.
 */
public final class AmbientSoundsSettings {
	private AmbientSoundsSettings() {
	}

	// ---------- General (AmbientSoundsConfig) ----------
	public static final FloatSetting VOLUME = new FloatSetting("general.volume", "Volume", "All ambient sounds together", 1, 0, 1, 0.01F, "");
	public static final BoolSetting MASTER = new BoolSetting("general.useSoundMasterSource", "Use the master channel",
		"Play on Minecraft's Master volume instead of Ambient/Environment", false);
	public static final IntSetting SCAN_STEPS = new IntSetting("general.scanStepAmount", "Scan step amount",
		"Blocks the surroundings scan checks before it rests for a moment. Lower: lighter, but slower to notice changes", 100, 10, 1000, 10, "");
	public static final BoolSetting OFFSET = new BoolSetting("general.playSoundWithOffset", "Start at a random point",
		"Looping sounds begin somewhere in the middle, so they don't repeat the same way", true);
	// ---------- Engine ----------
	public static final FloatSetting FADE_VOLUME = new FloatSetting("fade-volume", "Volume fade", "How fast sounds fade in and out, per tick", 0.005F,
		0.001F, 0.1F, 0.001F, "");
	public static final FloatSetting FADE_PITCH = new FloatSetting("fade-pitch", "Pitch fade", "How fast a sound's pitch changes, per tick", 0.005F,
		0.001F, 0.1F, 0.001F, "");
	public static final TextSetting SILENT_DIMENSIONS = new TextSetting("silent-dimensions", "Silent dimensions",
		"Dimensions with no ambient sounds, comma separated (* matches anything), like minecraft:the_end", "", 256);
	public static final BoolSetting DEBUG = new BoolSetting("debug", "Debug overlay",
		"Shows what the engine hears around you and what it plays (the mod's /ambient-debug)", false);

	/** The engine's dimensions, in its order. */
	public static final String[] DIMENSIONS = {"abyssalcraft", "betweenlands", "cave", "end", "galacticraft", "galacticraft.planets", "midnight",
		"nether", "surface", "twilightforest", "void"};
	/** The engine's sound categories and their parents ("" for none), in its order. */
	public static final String[][] CATEGORIES = {{"animal", ""}, {"bird", "animal"}, {"bird_day", "bird"}, {"bird_night", "bird"}, {"cave", ""},
		{"cicadas", "animal"}, {"cricket", "animal"}, {"cricket_day", "cricket"}, {"cricket_night", "cricket"}, {"suspense", ""}, {"weather", ""},
		{"wind", ""}};
	/** The engine's regions (dimension regions as "dimension.region") and each one's sounds, in its order. */
	public static final String[][] REGIONS = {
		{"abyssalcraft.dark", "suspense.suspense"},
		{"end.unknown", "suspense.suspense"},
		{"galacticraft.planets.wind", "wind.light-wind"},
		{"nether.suspense", "nether"},
		{"twilightforest.unknown", "suspense.suspense"},
		{"void.dark", "suspense.suspense"},
		{"beach", "beach"},
		{"cave.ambience", "cave", "cave-water", "cave-deep", "cave-large"},
		{"cave.deep-dark", "deep-dark", "water-droplets"},
		{"cave"},
		{"cave.lush", "cave-lush", "frog"},
		{"cold.artic", "heavy-wind"},
		{"cold", "wind.light-wind"},
		{"desert", "cicadas-desert", "wind.light-wind"},
		{"forest.cold"},
		{"forest", "bird-ambience"},
		{"forest.roofed", "bird-ambience-spooky"},
		{"grass", "cricket", "cricket-night", "cicadas-night"},
		{"highland", "heavy-wind", "wind-in-leaves"},
		{"jungle", "cricket-jungle", "cricket-jungle-night", "bird-ambience-jungle", "bird-ambience-jungle-night"},
		{"mesa", "wind-mesa"},
		{"mushroom_fields", "cicadas-mushroom-fields", "special-mushroom-fields"},
		{"ocean", "ocean", "wind.light-wind"},
		{"ocean_deep", "whale"},
		{"pale-garden", "pale-garden-ambience", "eyeblossom"},
		{"plains", "cricket", "cricket-night", "cicadas-night"},
		{"rain", "rain"},
		{"savanna", "cricket", "cicadas", "cicadas-desert", "cricket-warm-night", "bird-warm"},
		{"sky", "wind.light-wind"},
		{"space"},
		{"storm.away", "storm-away", "howling-wind"},
		{"storm.close", "storm-close", "wind"},
		{"surface"},
		{"swamp", "cricket-swamp", "cricket-warm-night", "frog"},
		{"taiga", "bird-ambience-huge"},
		{"underwater", "underwater", "underwater-deep"},
		{"underworld", "underworld"},
		{"warden", "warden"}};

	public static final FloatSetting[] DIMENSION_VOLUME = new FloatSetting[DIMENSIONS.length];
	public static final FloatSetting[] CATEGORY_VOLUME = new FloatSetting[CATEGORIES.length];
	/** Each region's overall volume, in {@link #REGIONS} order. */
	public static final FloatSetting[] REGION_VOLUME = new FloatSetting[REGIONS.length];
	/** Each region's sounds' volumes: {@code SOUND_VOLUME[region][sound]}, sounds in {@link #REGIONS} order after the region. */
	public static final FloatSetting[][] SOUND_VOLUME = new FloatSetting[REGIONS.length][];

	static {
		for (int i = 0; i < DIMENSIONS.length; i++) {
			DIMENSION_VOLUME[i] = volume("dimensions." + DIMENSIONS[i], label(DIMENSIONS[i]));
		}
		for (int i = 0; i < CATEGORIES.length; i++) {
			CATEGORY_VOLUME[i] = volume("categories." + CATEGORIES[i][0], label(CATEGORIES[i][0]));
		}
		for (int i = 0; i < REGIONS.length; i++) {
			String key = REGIONS[i][0].replace(".", "_");
			// A region with sounds gets a section of its own, where "Overall" says enough.
			REGION_VOLUME[i] = volume("regions." + key + ".overall", REGIONS[i].length > 1 ? "Overall" : label(REGIONS[i][0]));
			SOUND_VOLUME[i] = new FloatSetting[REGIONS[i].length - 1];
			for (int j = 1; j < REGIONS[i].length; j++) {
				SOUND_VOLUME[i][j - 1] = volume("regions." + key + "." + REGIONS[i][j], label(REGIONS[i][j]));
			}
		}
	}

	private static FloatSetting volume(String id, String label) {
		return new FloatSetting(id, label, null, 1, 0, 1, 0.01F, "");
	}

	/** "cave.deep-dark" → "Cave deep dark", "bird_day" → "Bird day". */
	public static String label(String name) {
		String words = name.replace('.', ' ').replace('-', ' ').replace('_', ' ').trim();
		return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
	}

	/** The regions with no sounds of their own (they're conditions other regions use): their volumes share one section. */
	static SettingDescriptor<?>[] silentRegions() {
		List<SettingDescriptor<?>> out = new ArrayList<>();
		for (int i = 0; i < REGIONS.length; i++) {
			if (REGIONS[i].length == 1) {
				out.add(REGION_VOLUME[i]);
			}
		}
		return out.toArray(new SettingDescriptor<?>[0]);
	}

	/** Region {@code i}'s section: its overall volume, then its sounds'. */
	static SettingDescriptor<?>[] region(int i) {
		List<SettingDescriptor<?>> out = new ArrayList<>();
		out.add(REGION_VOLUME[i]);
		out.addAll(List.of(SOUND_VOLUME[i]));
		return out.toArray(new SettingDescriptor<?>[0]);
	}
}
