package com.flintclient.client.control.platform.sound;

import static com.flintclient.client.control.model.mods.SoundPhysicsSettings.*;

import com.flintclient.client.control.model.ModuleState;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One immutable reading of Sound Physics' settings, made on the game thread whenever they change and read by the sound
 * thread: the numbers, and the reflectivity and occlusion of each block (by block first, then by sound type, then the
 * default, as Sound Physics Remastered's block files work).
 *
 * <p>Adapted from Sound Physics Remastered (GPL-3.0; see META-INF/licenses/GPL-3.0-SoundPhysicsRemastered.txt).
 */
final class SoundPhysicsParams {
	/** Minecraft's sound types, in {@code SoundPhysicsSettings.SOUND_TYPES} order (named in code: 1.21.x is obfuscated). */
	static final SoundType[] TYPES = {SoundType.WOOD, SoundType.GRAVEL, SoundType.GRASS, SoundType.LILY_PAD, SoundType.STONE, SoundType.METAL,
		SoundType.GLASS, SoundType.WOOL, SoundType.SAND, SoundType.SNOW, SoundType.POWDER_SNOW, SoundType.LADDER, SoundType.ANVIL,
		SoundType.SLIME_BLOCK, SoundType.HONEY_BLOCK, SoundType.WET_GRASS, SoundType.CORAL_BLOCK, SoundType.BAMBOO, SoundType.BAMBOO_SAPLING,
		SoundType.SCAFFOLDING, SoundType.SWEET_BERRY_BUSH, SoundType.CROP, SoundType.HARD_CROP, SoundType.VINE, SoundType.NETHER_WART,
		SoundType.LANTERN, SoundType.STEM, SoundType.NYLIUM, SoundType.FUNGUS, SoundType.ROOTS, SoundType.SHROOMLIGHT, SoundType.WEEPING_VINES,
		SoundType.TWISTING_VINES, SoundType.SOUL_SAND, SoundType.SOUL_SOIL, SoundType.BASALT, SoundType.WART_BLOCK, SoundType.NETHERRACK,
		SoundType.NETHER_BRICKS, SoundType.NETHER_SPROUTS, SoundType.NETHER_ORE, SoundType.BONE_BLOCK, SoundType.NETHERITE_BLOCK,
		SoundType.ANCIENT_DEBRIS, SoundType.LODESTONE, SoundType.CHAIN, SoundType.NETHER_GOLD_ORE, SoundType.GILDED_BLACKSTONE, SoundType.CANDLE,
		SoundType.AMETHYST, SoundType.AMETHYST_CLUSTER, SoundType.SMALL_AMETHYST_BUD, SoundType.MEDIUM_AMETHYST_BUD, SoundType.LARGE_AMETHYST_BUD,
		SoundType.TUFF, SoundType.CALCITE, SoundType.DRIPSTONE_BLOCK, SoundType.POINTED_DRIPSTONE, SoundType.COPPER, SoundType.CAVE_VINES,
		SoundType.SPORE_BLOSSOM, SoundType.AZALEA, SoundType.FLOWERING_AZALEA, SoundType.MOSS_CARPET, SoundType.MOSS, SoundType.BIG_DRIPLEAF,
		SoundType.SMALL_DRIPLEAF, SoundType.ROOTED_DIRT, SoundType.HANGING_ROOTS, SoundType.AZALEA_LEAVES, SoundType.SCULK_SENSOR,
		SoundType.SCULK_CATALYST, SoundType.SCULK, SoundType.SCULK_VEIN, SoundType.SCULK_SHRIEKER, SoundType.GLOW_LICHEN, SoundType.DEEPSLATE,
		SoundType.DEEPSLATE_BRICKS, SoundType.DEEPSLATE_TILES, SoundType.POLISHED_DEEPSLATE, SoundType.FROGLIGHT, SoundType.FROGSPAWN,
		SoundType.MANGROVE_ROOTS, SoundType.MUDDY_MANGROVE_ROOTS, SoundType.MUD, SoundType.MUD_BRICKS, SoundType.PACKED_MUD, SoundType.HANGING_SIGN,
		SoundType.NETHER_WOOD_HANGING_SIGN, SoundType.BAMBOO_WOOD_HANGING_SIGN, SoundType.BAMBOO_WOOD, SoundType.NETHER_WOOD, SoundType.CHERRY_WOOD,
		SoundType.CHERRY_SAPLING, SoundType.CHERRY_LEAVES, SoundType.CHERRY_WOOD_HANGING_SIGN, SoundType.CHISELED_BOOKSHELF,
		SoundType.SUSPICIOUS_SAND, SoundType.SUSPICIOUS_GRAVEL, SoundType.DECORATED_POT, SoundType.DECORATED_POT_CRACKED};
	/** The blocks of {@code SoundPhysicsSettings.OCCLUSION_BLOCKS}, in its order. */
	private static final Block[] OCCLUSION_BLOCK_IDS = {Blocks.JUKEBOX, Blocks.LAVA, Blocks.WATER};

	final ModuleState state;
	final int revision;
	final float attenuationFactor;
	final float reverbAttenuationDistance;
	final float reverbGain;
	final float reverbBrightness;
	final float reverbDistance;
	final float blockAbsorption;
	final float occlusionVariation;
	final float defaultReflectivity;
	final float defaultOcclusion;
	final float distanceAllowance;
	final float airAbsorption;
	final float underwaterFilter;
	final boolean evaluateAmbient;
	final int rayCount;
	final int rayBounces;
	final float nonFullBlockOcclusion;
	final int maxOcclusionRays;
	final float maxOcclusion;
	final boolean strictOcclusion;
	final boolean directionEvaluation;
	final boolean redirectNonOccluded;
	final boolean updateMoving;
	final int updateInterval;
	final double maxDistance;
	final boolean unsafeLevelAccess;
	final int cloneRange;
	final int cloneTicks;
	final int cloneDistance;
	final boolean voiceChat;
	final boolean hearSelf;
	final boolean debugLogging;
	final boolean occlusionLogging;
	final boolean environmentLogging;
	final boolean performanceLogging;
	final boolean renderBounces;
	final boolean renderOcclusion;
	private final IdentityHashMap<SoundType, Float> typeReflectivity = new IdentityHashMap<>();
	private final IdentityHashMap<SoundType, Float> typeOcclusion = new IdentityHashMap<>();
	private final IdentityHashMap<Block, Float> blockOcclusion = new IdentityHashMap<>();
	/** Sound rates: sound id → most per tick (only the ids set; the rest have no limit). */
	private final Map<Identifier, Integer> rates = new HashMap<>();

	SoundPhysicsParams(ModuleState s) {
		state = s;
		revision = s.revision();
		attenuationFactor = s.decimal(ATTENUATION_FACTOR);
		reverbAttenuationDistance = s.decimal(REVERB_ATTENUATION_DISTANCE);
		reverbGain = s.decimal(REVERB_GAIN);
		reverbBrightness = s.decimal(REVERB_BRIGHTNESS);
		reverbDistance = s.decimal(REVERB_DISTANCE);
		blockAbsorption = s.decimal(BLOCK_ABSORPTION);
		occlusionVariation = s.decimal(OCCLUSION_VARIATION);
		defaultReflectivity = s.decimal(DEFAULT_REFLECTIVITY);
		defaultOcclusion = s.decimal(DEFAULT_OCCLUSION);
		distanceAllowance = s.decimal(DISTANCE_ALLOWANCE);
		airAbsorption = s.decimal(AIR_ABSORPTION);
		underwaterFilter = s.decimal(UNDERWATER_FILTER);
		evaluateAmbient = s.bool(AMBIENT_SOUNDS);
		rayCount = s.integer(RAY_COUNT);
		rayBounces = s.integer(RAY_BOUNCES);
		nonFullBlockOcclusion = s.decimal(NON_FULL_BLOCK_OCCLUSION);
		maxOcclusionRays = s.integer(MAX_OCCLUSION_RAYS);
		maxOcclusion = s.decimal(MAX_OCCLUSION);
		strictOcclusion = s.bool(STRICT_OCCLUSION);
		directionEvaluation = s.bool(DIRECTION_EVALUATION);
		redirectNonOccluded = s.bool(REDIRECT_NON_OCCLUDED);
		updateMoving = s.bool(UPDATE_MOVING);
		updateInterval = s.integer(UPDATE_INTERVAL);
		maxDistance = s.decimal(MAX_DISTANCE);
		unsafeLevelAccess = s.bool(UNSAFE_LEVEL_ACCESS);
		cloneRange = s.integer(CLONE_RANGE);
		cloneTicks = s.integer(CLONE_TICKS);
		cloneDistance = s.integer(CLONE_DISTANCE);
		voiceChat = s.bool(VOICE_CHAT);
		hearSelf = s.bool(HEAR_SELF);
		debugLogging = s.bool(DEBUG_LOGGING);
		occlusionLogging = s.bool(OCCLUSION_LOGGING);
		environmentLogging = s.bool(ENVIRONMENT_LOGGING);
		performanceLogging = s.bool(PERFORMANCE_LOGGING);
		renderBounces = s.bool(RENDER_BOUNCES);
		renderOcclusion = s.bool(RENDER_OCCLUSION);
		for (int i = 0; i < TYPES.length; i++) {
			typeReflectivity.put(TYPES[i], s.decimal(REFLECTIVITY[i]));
			typeOcclusion.put(TYPES[i], s.decimal(OCCLUSION[i]));
		}
		for (int i = 0; i < OCCLUSION_BLOCK_IDS.length; i++) {
			blockOcclusion.put(OCCLUSION_BLOCK_IDS[i], s.decimal(OCCLUSION_BLOCK[i]));
		}
		for (Map.Entry<String, Integer> e : s.values(SOUND_RATES).entrySet()) {
			Identifier id = Identifier.tryParse(e.getKey());
			if (id != null) {
				rates.put(id, e.getValue());
			}
		}
	}

	/** How much sound {@code state} reflects (block files: by sound type, else the default). */
	float reflectivity(BlockState state) {
		Float value = typeReflectivity.get(state.getSoundType());
		return value != null ? value : defaultReflectivity;
	}

	/** How much sound {@code state} absorbs (by block, then sound type, else the default). */
	float occlusion(BlockState state) {
		Float value = blockOcclusion.get(state.getBlock());
		if (value != null) {
			return value;
		}
		value = typeOcclusion.get(state.getSoundType());
		return value != null ? value : defaultOcclusion;
	}

	/** Most of {@code sound} per tick that gets sound physics; {@link Integer#MAX_VALUE} for no limit. */
	int maxPerTick(Identifier sound) {
		Integer count = rates.get(sound);
		return count == null || count <= -1 ? Integer.MAX_VALUE : count;
	}

	/** The id {@code block} goes by in the block files, for logs. */
	static String id(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}
}
