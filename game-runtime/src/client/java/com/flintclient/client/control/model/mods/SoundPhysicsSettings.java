package com.flintclient.client.control.model.mods;

import com.flintclient.client.control.model.BoolSetting;
import com.flintclient.client.control.model.FloatSetting;
import com.flintclient.client.control.model.IdKind;
import com.flintclient.client.control.model.IdValuesSetting;
import com.flintclient.client.control.model.IntSetting;
import com.flintclient.client.control.model.SettingDescriptor;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sound Physics' settings: every option of Sound Physics Remastered (1.5.2, GPL-3.0, by Sonic Ether, vlad2305m, Max
 * Henkel and Saint), with its key, range, default and meaning. The block tables are the mod's reflectivity and occlusion
 * files: one value per block sound type (and per block where the mod names one), with the mod's own starting values.
 */
public final class SoundPhysicsSettings {
	private SoundPhysicsSettings() {
	}

	// ---------- General ----------
	public static final BoolSetting VOICE_CHAT = new BoolSetting("simple_voice_chat_integration", "Simple Voice Chat",
		"Sound effects on voices from Simple Voice Chat", true);
	public static final BoolSetting HEAR_SELF = new BoolSetting("simple_voice_chat_hear_self", "Hear yourself",
		"Hear your own voice's echo with Simple Voice Chat", false);
	public static final FloatSetting ATTENUATION_FACTOR = new FloatSetting("attenuation_factor", "Attenuation factor",
		"How quickly sounds get quieter with distance. Lower: distant sounds are louder. 1.0 is physically correct", 1, 0.1F, 1, 0.01F, "");
	public static final FloatSetting REVERB_ATTENUATION_DISTANCE = new FloatSetting("reverb_attenuation_distance", "Reverb starts at",
		"The ray distance at which reverb starts (0: from the start)", 0, 0, 512, 0.5F, " blocks");
	public static final FloatSetting REVERB_GAIN = new FloatSetting("reverb_gain", "Reverb gain", "The volume of the simulated echo", 1, 0.1F, 2,
		0.01F, "");
	public static final FloatSetting REVERB_BRIGHTNESS = new FloatSetting("reverb_brightness", "Reverb brightness",
		"Higher: more high frequencies in the echo. Lower: a more muffled echo", 1, 0.1F, 2, 0.01F, "");
	public static final FloatSetting REVERB_DISTANCE = new FloatSetting("reverb_distance", "Reverb distance",
		"How far the echo carries, relative to the sound's own distance", 1.5F, 0.1F, 16, 0.05F, "×");
	public static final FloatSetting BLOCK_ABSORPTION = new FloatSetting("block_absorption", "Block absorption",
		"How much sound blocks absorb when it travels through them", 1, 0.1F, 4, 0.01F, "");
	public static final FloatSetting OCCLUSION_VARIATION = new FloatSetting("occlusion_variation", "Occlusion variation",
		"Higher: smaller objects don't count as in the way", 0.35F, 0, 16, 0.01F, "");
	public static final FloatSetting DEFAULT_REFLECTIVITY = new FloatSetting("default_block_reflectivity", "Block reflectivity",
		"How much sound blocks reflect, for blocks the reflectivity list doesn't name. Lower: shorter echo tails", 0.5F, 0.1F, 4, 0.01F, "");
	public static final FloatSetting DEFAULT_OCCLUSION = new FloatSetting("default_block_occlusion_factor", "Block occlusion",
		"How much blocks muffle sound, for blocks the occlusion list doesn't name. Higher: thick walls block sounds", 1, 0, 10, 0.01F, "");
	public static final FloatSetting DISTANCE_ALLOWANCE = new FloatSetting("sound_distance_allowance", "Distance allowance",
		"How far away a sound may be and still play, times Minecraft's limit. In singleplayer worlds", 4, 1, 6, 0.1F, "×");
	public static final FloatSetting AIR_ABSORPTION = new FloatSetting("air_absorption", "Air absorption",
		"How much air dulls high frequencies with distance (1.0: normal air, 0: off)", 1, 0, 5, 0.01F, "");
	public static final FloatSetting UNDERWATER_FILTER = new FloatSetting("underwater_filter", "Underwater filter",
		"How much sound is muffled under water (0: not at all, 1: fully)", 0.9F, 0, 1, 0.01F, "");
	public static final BoolSetting AMBIENT_SOUNDS = new BoolSetting("evaluate_ambient_sounds", "Ambient sounds too",
		"Cave, Nether and underwater ambience get sound physics as well", false);

	// ---------- Performance ----------
	public static final IntSetting RAY_COUNT = new IntSetting("environment_evaluation_ray_count", "Rays per sound",
		"Rays traced to find a sound's echo. More: steadier, but slower", 32, 8, 64, 1, "");
	public static final IntSetting RAY_BOUNCES = new IntSetting("environment_evaluation_ray_bounces", "Ray bounces",
		"Bounces per ray. More: more echo and sound carried along tunnels, but slower", 4, 2, 64, 1, "");
	public static final FloatSetting NON_FULL_BLOCK_OCCLUSION = new FloatSetting("non_full_block_occlusion_factor", "Partial occlusion",
		"A block side that isn't a full square muffles this much of a full block", 0.25F, 0, 1, 0.01F, "×");
	public static final IntSetting MAX_OCCLUSION_RAYS = new IntSetting("max_occlusion_rays", "Occlusion rays",
		"How many blocks between you and a sound are counted", 16, 1, 128, 1, "");
	public static final FloatSetting MAX_OCCLUSION = new FloatSetting("max_occlusion", "Maximum occlusion", "Muffling stops growing here", 64, 0,
		1024, 0.5F, "");
	public static final BoolSetting STRICT_OCCLUSION = new BoolSetting("strict_occlusion", "Strict occlusion",
		"One path between a sound and you instead of nine", false);
	public static final BoolSetting DIRECTION_EVALUATION = new BoolSetting("sound_direction_evaluation", "Sound direction",
		"A sound behind a wall comes from where its echo reaches you", true);
	public static final BoolSetting REDIRECT_NON_OCCLUDED = new BoolSetting("redirect_non_occluded_sounds", "Redirect visible sounds",
		"Off: sounds you can see aren't moved", true);
	public static final BoolSetting UPDATE_MOVING = new BoolSetting("update_moving_sounds", "Update long sounds",
		"Music discs and other long sounds are worked out again as they play", false);
	public static final IntSetting UPDATE_INTERVAL = new IntSetting("sound_update_interval", "Update interval",
		"Ticks between updates of long sounds. Lower: more often, but slower", 5, 1, 1200, 1, " ticks");
	public static final FloatSetting MAX_DISTANCE = new FloatSetting("max_sound_processing_distance", "Processing distance",
		"Sounds farther away than this play without sound physics", 512, 0, 4096, 1, " blocks");
	public static final BoolSetting UNSAFE_LEVEL_ACCESS = new BoolSetting("unsafe_level_access", "Direct world access",
		"Reads the world itself instead of a copy. Can cause problems with other mods", false);
	public static final IntSetting CLONE_RANGE = new IntSetting("level_clone_range", "World copy radius", "Chunks copied around you for the rays", 4,
		2, 16, 1, " chunks");
	public static final IntSetting CLONE_TICKS = new IntSetting("level_clone_max_retain_ticks", "World copy lifetime",
		"Ticks before the copy is made again", 20, 1, 1200, 1, " ticks");
	public static final IntSetting CLONE_DISTANCE = new IntSetting("level_clone_max_retain_block_distance", "World copy distance",
		"How far you can move from where the copy was made before it's made again", 16, 1, 1024, 1, " blocks");
	/** Sound Physics Remastered's sound_rates.properties: most sounds per tick (−1 no limit, 0 never). */
	public static final IdValuesSetting SOUND_RATES = new IdValuesSetting("sound_rates", "Sound rates",
		"Most of each sound per tick that get sound physics (−1: no limit, 0: never). Keeps mass-produced sounds from lagging", IdKind.SOUND, -1,
		-1, 100, 1, "", soundRateDefaults());

	// ---------- Debug ----------
	public static final BoolSetting DEBUG_LOGGING = new BoolSetting("debug_logging", "Debug logging", "Writes each sound's details to the log",
		false);
	public static final BoolSetting OCCLUSION_LOGGING = new BoolSetting("occlusion_logging", "Occlusion logging",
		"Writes what muffles each sound to the log", false);
	public static final BoolSetting ENVIRONMENT_LOGGING = new BoolSetting("environment_logging", "Environment logging",
		"Writes each sound's echo values to the log", false);
	public static final BoolSetting PERFORMANCE_LOGGING = new BoolSetting("performance_logging", "Performance logging",
		"Writes how long the work takes to the log", false);
	public static final BoolSetting RENDER_BOUNCES = new BoolSetting("render_sound_bounces", "Show sound bounces",
		"Draws each sound's rays in the world", false);
	public static final BoolSetting RENDER_OCCLUSION = new BoolSetting("render_occlusion", "Show occlusion",
		"Draws the path from each sound to you, green when clear, red when blocked", false);

	// ---------- Block reflectivity and occlusion ----------
	/** Block sound types the mod's files list, in its order (Minecraft's {@code SoundType} constant names). */
	public static final String[] SOUND_TYPES = {"WOOD", "GRAVEL", "GRASS", "LILY_PAD", "STONE", "METAL", "GLASS", "WOOL", "SAND", "SNOW",
		"POWDER_SNOW", "LADDER", "ANVIL", "SLIME_BLOCK", "HONEY_BLOCK", "WET_GRASS", "CORAL_BLOCK", "BAMBOO", "BAMBOO_SAPLING", "SCAFFOLDING",
		"SWEET_BERRY_BUSH", "CROP", "HARD_CROP", "VINE", "NETHER_WART", "LANTERN", "STEM", "NYLIUM", "FUNGUS", "ROOTS", "SHROOMLIGHT",
		"WEEPING_VINES", "TWISTING_VINES", "SOUL_SAND", "SOUL_SOIL", "BASALT", "WART_BLOCK", "NETHERRACK", "NETHER_BRICKS", "NETHER_SPROUTS",
		"NETHER_ORE", "BONE_BLOCK", "NETHERITE_BLOCK", "ANCIENT_DEBRIS", "LODESTONE", "CHAIN", "NETHER_GOLD_ORE", "GILDED_BLACKSTONE", "CANDLE",
		"AMETHYST", "AMETHYST_CLUSTER", "SMALL_AMETHYST_BUD", "MEDIUM_AMETHYST_BUD", "LARGE_AMETHYST_BUD", "TUFF", "CALCITE", "DRIPSTONE_BLOCK",
		"POINTED_DRIPSTONE", "COPPER", "CAVE_VINES", "SPORE_BLOSSOM", "AZALEA", "FLOWERING_AZALEA", "MOSS_CARPET", "MOSS", "BIG_DRIPLEAF",
		"SMALL_DRIPLEAF", "ROOTED_DIRT", "HANGING_ROOTS", "AZALEA_LEAVES", "SCULK_SENSOR", "SCULK_CATALYST", "SCULK", "SCULK_VEIN",
		"SCULK_SHRIEKER", "GLOW_LICHEN", "DEEPSLATE", "DEEPSLATE_BRICKS", "DEEPSLATE_TILES", "POLISHED_DEEPSLATE", "FROGLIGHT", "FROGSPAWN",
		"MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS", "MUD", "MUD_BRICKS", "PACKED_MUD", "HANGING_SIGN", "NETHER_WOOD_HANGING_SIGN",
		"BAMBOO_WOOD_HANGING_SIGN", "BAMBOO_WOOD", "NETHER_WOOD", "CHERRY_WOOD", "CHERRY_SAPLING", "CHERRY_LEAVES", "CHERRY_WOOD_HANGING_SIGN",
		"CHISELED_BOOKSHELF", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "DECORATED_POT", "DECORATED_POT_CRACKED"};
	/** Blocks the occlusion file names on their own (block id → its occlusion). */
	public static final String[] OCCLUSION_BLOCKS = {"minecraft:jukebox", "minecraft:lava", "minecraft:water"};

	/** One reflectivity per sound type, in {@link #SOUND_TYPES} order. */
	public static final FloatSetting[] REFLECTIVITY = new FloatSetting[SOUND_TYPES.length];
	/** One occlusion per sound type, in {@link #SOUND_TYPES} order. */
	public static final FloatSetting[] OCCLUSION = new FloatSetting[SOUND_TYPES.length];
	/** One occlusion per block of {@link #OCCLUSION_BLOCKS}. */
	public static final FloatSetting[] OCCLUSION_BLOCK = new FloatSetting[OCCLUSION_BLOCKS.length];

	static {
		Map<String, Float> reflect = new LinkedHashMap<>();
		reflect.put("STONE", 1.5F);
		reflect.put("NETHERITE_BLOCK", 1.5F);
		reflect.put("TUFF", 1.5F);
		reflect.put("AMETHYST", 1.5F);
		reflect.put("BASALT", 1.5F);
		reflect.put("CALCITE", 1.5F);
		reflect.put("BONE_BLOCK", 1.5F);
		reflect.put("COPPER", 1.25F);
		reflect.put("DEEPSLATE", 1.5F);
		reflect.put("DEEPSLATE_BRICKS", 1.5F);
		reflect.put("DEEPSLATE_TILES", 1.5F);
		reflect.put("POLISHED_DEEPSLATE", 1.5F);
		reflect.put("NETHER_BRICKS", 1.5F);
		reflect.put("NETHERRACK", 1.1F);
		reflect.put("NETHER_GOLD_ORE", 1.1F);
		reflect.put("NETHER_ORE", 1.1F);
		reflect.put("STEM", 0.4F);
		reflect.put("WOOL", 0.1F);
		reflect.put("HONEY_BLOCK", 0.1F);
		reflect.put("MOSS", 0.1F);
		reflect.put("SOUL_SAND", 0.2F);
		reflect.put("SOUL_SOIL", 0.2F);
		reflect.put("CORAL_BLOCK", 0.2F);
		reflect.put("METAL", 1.25F);
		reflect.put("WOOD", 0.4F);
		reflect.put("GRAVEL", 0.3F);
		reflect.put("GRASS", 0.3F);
		reflect.put("GLASS", 0.75F);
		reflect.put("SAND", 0.2F);
		reflect.put("SNOW", 0.15F);
		Map<String, Float> occlude = new LinkedHashMap<>();
		occlude.put("WOOL", 1.5F);
		occlude.put("MOSS", 0.75F);
		occlude.put("HONEY_BLOCK", 0.5F);
		for (String light : new String[] {"GLASS", "SNOW", "POWDER_SNOW", "BAMBOO", "BAMBOO_SAPLING", "WET_GRASS", "MOSS_CARPET"}) {
			occlude.put(light, 0.1F);
		}
		for (String none : new String[] {"WEEPING_VINES", "TWISTING_VINES", "VINE", "SWEET_BERRY_BUSH", "SPORE_BLOSSOM", "SMALL_DRIPLEAF", "ROOTS",
			"POINTED_DRIPSTONE", "SCAFFOLDING", "GLOW_LICHEN", "CROP", "FUNGUS", "LILY_PAD", "LARGE_AMETHYST_BUD", "MEDIUM_AMETHYST_BUD",
			"SMALL_AMETHYST_BUD", "LADDER", "CHAIN"}) {
			occlude.put(none, 0F);
		}
		for (int i = 0; i < SOUND_TYPES.length; i++) {
			String type = SOUND_TYPES[i];
			String key = type.toLowerCase(Locale.ROOT);
			String name = typeName(type);
			REFLECTIVITY[i] = new FloatSetting("reflectivity." + key, name, null, reflect.getOrDefault(type, DEFAULT_REFLECTIVITY.defaultValue()), 0.01F,
				10, 0.01F, "");
			OCCLUSION[i] = new FloatSetting("occlusion." + key, name, null, occlude.getOrDefault(type, DEFAULT_OCCLUSION.defaultValue()), 0, 10, 0.01F,
				"");
		}
		float[] blockDefaults = {0, 0.75F, 0.25F};
		String[] blockNames = {"Jukebox (block)", "Lava (block)", "Water (block)"};
		for (int i = 0; i < OCCLUSION_BLOCKS.length; i++) {
			OCCLUSION_BLOCK[i] = new FloatSetting("occlusion." + OCCLUSION_BLOCKS[i], blockNames[i], null, blockDefaults[i], 0, 10, 0.01F, "");
		}
	}

	/** "SMALL_AMETHYST_BUD" → "Small Amethyst Bud", as the mod names its sound types. */
	public static String typeName(String type) {
		StringBuilder out = new StringBuilder();
		for (String word : type.split("_")) {
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	/** Rain, thunder and goat horns are left out of sound physics, as the mod ships them. */
	private static Map<String, Integer> soundRateDefaults() {
		Map<String, Integer> out = new LinkedHashMap<>();
		out.put("minecraft:weather.rain", 0);
		out.put("minecraft:weather.rain.above", 0);
		out.put("minecraft:entity.lightning_bolt.thunder", 0);
		for (int i = 0; i < 8; i++) {
			out.put("minecraft:item.goat_horn.sound." + i, 0);
		}
		return out;
	}

	/** Every setting of the module's reflectivity section, then its occlusion section. */
	static SettingDescriptor<?>[] occlusionSection() {
		SettingDescriptor<?>[] out = new SettingDescriptor<?>[OCCLUSION.length + OCCLUSION_BLOCK.length];
		System.arraycopy(OCCLUSION, 0, out, 0, OCCLUSION.length);
		System.arraycopy(OCCLUSION_BLOCK, 0, out, OCCLUSION.length, OCCLUSION_BLOCK.length);
		return out;
	}
}
