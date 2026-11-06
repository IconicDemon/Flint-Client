/*
 * Adapted from Falling Leaves (https://github.com/RandomMcSomethin/fallingleaves) by RandomMcSomethin, Fourmisain and
 * BrekiTomasson, MIT licence (META-INF/licenses/MIT-FallingLeaves.txt): its spawning rules (LeafUtil), leaf settings
 * (LeafSettingsEntry, ConfigDefaults), wind (Wind, SmoothNoise, TriangularDistribution) and leaf colours.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.FallingLeaves;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Falling Leaves: leaves (and snowflakes in snowy places) falling from leaf blocks as Minecraft animates them, from a leaf
 * block that's hit or decays, and drifting on a wind of their own. Render thread (decay from the integrated server is
 * handed over to it). Fault-isolated: a failure leaves Minecraft's own falling leaves.
 */
public final class Mc263FallingLeaves {
	private static final int MODULE = Mods.index(FallingLeaves.ID);
	private static final HookGuard GUARD = new HookGuard("falling leaves", (path, error) -> FlintClient.LOGGER.warn(
		"Flint: the {} hook failed; it's off for this session", path, error));
	private static final float DEFAULT_SPAWN_CHANCE = 0.01F;
	private static final Identifier PARTICLES_ATLAS = Identifier.withDefaultNamespace("particles");
	private static final String[] LEAF_SPRITES = {"falling_leaf_1", "falling_leaf_2", "falling_leaf_3", "falling_leaf_4", "falling_leaf_5"};
	private static final String[] CONIFER_SPRITES = {"falling_leaf_conifer_1", "falling_leaf_conifer_2", "falling_leaf_conifer_3"};

	/** The wind now, in blocks a tick (leaves ease toward it). */
	static float windX;
	static float windZ;

	private Mc263FallingLeaves() {
	}

	/** Whether falling leaves are on (the module, and no failure). */
	public static boolean on() {
		return GUARD.on() && Mods.on(MODULE);
	}

	// ---- Leaf settings: one entry per block, worked out once per settings change.

	private enum Implementation {
		VANILLA,
		REGULAR,
		CONIFER
	}

	private record Entry(Implementation implementation, float spawnRateFactor, boolean conifer, boolean spawnBreakingLeaves) {
	}

	private static final Map<Block, Optional<Entry>> ENTRIES = new IdentityHashMap<>();
	private static int entriesFor = -1;
	private static Set<String> conifers = Set.of();
	private static Set<String> vanillaBlocks = Set.of();
	private static Set<String> noBreaking = Set.of();
	private static Map<String, Float> rates = Map.of();
	private static Set<String> windless = Set.of();
	private static Map<String, List<String[]>> spawners = Map.of();

	private static void refresh(ModuleState s) {
		if (s.revision() == entriesFor) {
			return;
		}
		entriesFor = s.revision();
		ENTRIES.clear();
		conifers = ids(s.text(FallingLeaves.CONIFERS));
		vanillaBlocks = ids(s.text(FallingLeaves.VANILLA_BLOCKS));
		noBreaking = ids(s.text(FallingLeaves.NO_BREAKING));
		windless = ids(s.text(FallingLeaves.WINDLESS));
		Map<String, Float> r = new HashMap<>();
		for (String pair : s.text(FallingLeaves.BLOCK_RATES).split(",")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				try {
					r.put(id(pair.substring(0, eq)), Float.parseFloat(pair.substring(eq + 1).strip()) / 100F);
				} catch (NumberFormatException e) {
					// Not a number: that block keeps its normal rate.
				}
			}
		}
		rates = r;
		Map<String, List<String[]>> sp = new HashMap<>();
		for (String spawner : s.text(FallingLeaves.SPAWNERS).split(",")) {
			String text = spawner.strip();
			if (text.isEmpty()) {
				continue;
			}
			int bracket = text.indexOf('[');
			String block = id(bracket < 0 ? text : text.substring(0, bracket));
			List<String[]> props = new ArrayList<>();
			if (bracket >= 0) {
				String inner = text.substring(bracket + 1, text.endsWith("]") ? text.length() - 1 : text.length());
				for (String prop : inner.split(";|\\s+")) {
					int eq = prop.indexOf('=');
					if (eq > 0) {
						props.add(new String[] {prop.substring(0, eq).strip(), prop.substring(eq + 1).strip()});
					}
				}
			}
			sp.put(block, props);
		}
		spawners = sp;
	}

	private static Set<String> ids(String list) {
		Set<String> out = new HashSet<>();
		for (String part : list.split(",")) {
			String id = part.strip();
			if (!id.isEmpty()) {
				out.add(id(id));
			}
		}
		return out;
	}

	/** "oak_leaves" as "minecraft:oak_leaves". */
	private static String id(String text) {
		String t = text.strip().toLowerCase(Locale.ROOT);
		return t.indexOf(':') < 0 ? "minecraft:" + t : t;
	}

	private static boolean isLeafBlock(Block block) {
		return block instanceof LeavesBlock || block.defaultBlockState().is(BlockTags.LEAVES);
	}

	/** The block's leaf settings, or null when it drops no leaves (not a leaf block, nor a leaf spawner). */
	private static @Nullable Entry entry(ModuleState s, BlockState state) {
		refresh(s);
		Block block = state.getBlock();
		Optional<Entry> known = ENTRIES.get(block);
		if (known == null) {
			String id = BuiltInRegistries.BLOCK.getKey(block).toString();
			Entry made = null;
			if (isLeafBlock(block) || spawners.containsKey(id)) {
				boolean conifer = conifers.contains(id);
				Implementation impl = vanillaBlocks.contains(id) ? Implementation.VANILLA : conifer ? Implementation.CONIFER : Implementation.REGULAR;
				made = new Entry(impl, rates.getOrDefault(id, 1F), conifer, !noBreaking.contains(id));
			}
			known = Optional.ofNullable(made);
			ENTRIES.put(block, known);
		}
		return known.orElse(null);
	}

	private static Implementation implementation(ModuleState s, Entry e) {
		return s.bool(FallingLeaves.VANILLA_PARTICLES) ? Implementation.VANILLA : e.implementation;
	}

	private static float spawnChance(ModuleState s, Entry e) {
		int rate = e.conifer ? s.integer(FallingLeaves.CONIFER_RATE) : s.integer(FallingLeaves.SPAWN_RATE);
		return e.spawnRateFactor * rate / 20F * DEFAULT_SPAWN_CHANCE;
	}

	private static boolean insideStartRadius(ModuleState s, BlockPos pos) {
		int radius = s.integer(FallingLeaves.START_RADIUS);
		if (radius == 0) {
			return false;
		}
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		BlockPos at = player.blockPosition();
		int d = Math.max(Math.abs(at.getX() - pos.getX()), Math.max(Math.abs(at.getY() - pos.getY()), Math.abs(at.getZ() - pos.getZ())));
		return d < radius;
	}

	private static float modifiedChance(ModuleState s, BlockPos pos, BlockState state, @Nullable Entry e) {
		if (e == null || insideStartRadius(s, pos)) {
			return 0;
		}
		if (!s.bool(FallingLeaves.PLAYER_PLACED) && state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT)) {
			return 0;
		}
		float chance = spawnChance(s, e);
		float decay = s.decimal(FallingLeaves.DECAY_FACTOR);
		if (decay != 1 && isLeafBlock(state.getBlock()) && state.isRandomlyTicking()) {
			chance *= decay;
		}
		return chance;
	}

	// ---- Hooks.

	/** A leaf block's animation tick (LeavesBlock.animateTick): its leaves and snowflakes. */
	public static void leafTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (!on()) {
			return;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			Entry e = entry(s, state);
			if (e != null && implementation(s, e) != Implementation.VANILLA) {
				float chance = modifiedChance(s, pos, state, e);
				if (chance != 0 && random.nextFloat() < chance) {
					spawnLeaves(s, 1, false, state, level, pos, random, e);
				}
			}
			trySnow(s, state, level, pos, random);
		} catch (RuntimeException | LinkageError ex) {
			GUARD.fail(ex);
		}
	}

	/** Any block's animation tick: a leaf spawner (Experimental) drops leaves and snowflakes too. */
	public static void blockTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (!on()) {
			return;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			refresh(s);
			if (spawners.isEmpty() || isLeafBlock(state.getBlock())) {
				return;
			}
			List<String[]> props = spawners.get(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
			if (props == null) {
				return;
			}
			for (String[] prop : props) {
				for (var property : state.getProperties()) {
					if (property.getName().equals(prop[0]) && !state.getValue(property).toString().equalsIgnoreCase(prop[1])) {
						return;
					}
				}
			}
			trySnow(s, state, level, pos, random);
			Entry e = entry(s, state);
			if (e != null) {
				float chance = modifiedChance(s, pos, state, e);
				if (chance != 0 && random.nextFloat() < chance) {
					spawnLeaves(s, 1, false, state, level, pos, random, e);
				}
			}
		} catch (RuntimeException | LinkageError ex) {
			GUARD.fail(ex);
		}
	}

	/**
	 * Minecraft's own falling-leaf chance for {@code state}: Falling Leaves' rate when the block keeps vanilla particles, and
	 * never (-1) when Flint drops its own leaves instead.
	 */
	public static float vanillaChance(BlockState state, BlockPos pos, float vanilla) {
		if (!on()) {
			return vanilla;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			Entry e = entry(s, state);
			if (e == null) {
				return vanilla;
			}
			return implementation(s, e) == Implementation.VANILLA ? modifiedChance(s, pos, state, e) : -1;
		} catch (RuntimeException | LinkageError ex) {
			GUARD.fail(ex);
			return vanilla;
		}
	}

	/** A leaf block decayed in the integrated server's world: leaves and snowflakes burst from it in yours. */
	public static void decayed(BlockState state, BlockPos pos) {
		if (!on() || Mods.state(MODULE).integer(FallingLeaves.MAX_DECAY) == 0) {
			return;
		}
		Minecraft.getInstance().execute(() -> {
			try {
				ClientLevel level = Minecraft.getInstance().level;
				if (level == null || !on()) {
					return;
				}
				ModuleState s = Mods.state(MODULE);
				int max = s.integer(FallingLeaves.MAX_DECAY);
				Entry e = entry(s, state);
				RandomSource random = level.getRandom();
				if (e != null && e.spawnBreakingLeaves) {
					spawnLeaves(s, coins(random, max), true, state, level, pos, random, e);
				}
				spawnSnow(s, coins(random, 2 * max), true, state, level, pos, random);
			} catch (RuntimeException | LinkageError ex) {
				GUARD.fail(ex);
			}
		});
	}

	/** You started breaking a block (Leaves on block hit). */
	public static void hit(BlockPos pos) {
		if (!on()) {
			return;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			ClientLevel level = Minecraft.getInstance().level;
			if (level == null || !s.bool(FallingLeaves.ON_HIT)) {
				return;
			}
			BlockState state = level.getBlockState(pos);
			Entry e = entry(s, state);
			if (e == null) {
				return;
			}
			RandomSource random = level.getRandom();
			if (e.spawnBreakingLeaves) {
				spawnLeaves(s, coins(random, 3), false, state, level, pos, random, e);
			}
			if (s.integer(FallingLeaves.SNOW_RATE) != 0) {
				spawnSnow(s, coins(random, 6), false, state, level, pos, random);
			}
		} catch (RuntimeException | LinkageError ex) {
			GUARD.fail(ex);
		}
	}

	private static int coins(RandomSource random, int n) {
		int count = 0;
		for (int i = 0; i < n; i++) {
			if (random.nextBoolean()) {
				count++;
			}
		}
		return count;
	}

	// ---- Spawning.

	private static void trySnow(ModuleState s, BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (insideStartRadius(s, pos)) {
			return;
		}
		double chance = s.integer(FallingLeaves.SNOW_RATE) / 20F * DEFAULT_SPAWN_CHANCE;
		if (chance != 0 && random.nextDouble() < chance) {
			spawnSnow(s, 1, false, state, level, pos, random);
		}
	}

	private static void spawnLeaves(ModuleState s, int count, boolean inside, BlockState state, Level level, BlockPos pos, RandomSource random,
		Entry e) {
		if (count == 0) {
			return;
		}
		if (implementation(s, e) == Implementation.VANILLA && state.getBlock() instanceof VanillaLeaves vanilla) {
			for (int i = 0; i < count; i++) {
				vanilla.flint$spawnVanillaLeaf(level, pos, random);
			}
			return;
		}
		int kind = implementation(s, e) == Implementation.CONIFER ? FallingLeafParticle.CONIFER : FallingLeafParticle.LEAF;
		spawn(s, kind, count, inside, state, level, pos, random);
	}

	private static void spawnSnow(ModuleState s, int count, boolean inside, BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (count == 0) {
			return;
		}
		boolean snowy = VillagerType.byBiome(level.getBiome(pos)) == VillagerType.SNOW;
		if (!snowy) {
			Block top = level.getBlockState(level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).below()).getBlock();
			snowy = top instanceof SnowLayerBlock || top == Blocks.SNOW_BLOCK || top instanceof PowderSnowBlock;
		}
		if (snowy) {
			spawn(s, FallingLeafParticle.SNOW, count, inside, state, level, pos, random);
		}
	}

	private static void spawn(ModuleState s, int kind, int count, boolean inside, BlockState state, Level level, BlockPos pos, RandomSource random) {
		Minecraft mc = Minecraft.getInstance();
		if (!(level instanceof ClientLevel client)) {
			return;
		}
		for (int i = 0; i < count; i++) {
			double x = pos.getX() + random.nextDouble();
			double z = pos.getZ() + random.nextDouble();
			double y;
			if (inside) {
				y = pos.getY() + random.nextDouble();
			} else {
				y = pos.getY() - (state.canOcclude() ? 0.1 : 0.0);
				if (!hasRoom(s, level, pos, x, y, z)) {
					continue;
				}
			}
			TextureAtlasSprite sprite = sprite(s, kind, random);
			if (sprite == null) {
				return;
			}
			double r = 1;
			double g = 1;
			double b = 1;
			if (kind != FallingLeafParticle.SNOW) {
				double[] color = leafColor(state, client, BlockPos.containing(x, y, z));
				r = color[0];
				g = color[1];
				b = color[2];
			}
			mc.particleEngine.add(new FallingLeafParticle(kind, client, x, y, z, r, g, b, sprite, s.integer(FallingLeaves.LIFESPAN),
				s.integer(FallingLeaves.LEAF_SIZE) / 50F));
		}
	}

	private static boolean hasRoom(ModuleState s, Level level, BlockPos pos, double x, double y, double z) {
		if (isLeafBlock(level.getBlockState(pos.below()).getBlock())) {
			return false;
		}
		double y2 = y - s.integer(FallingLeaves.FREE_SPACE) * 0.5;
		AABB box = new AABB(x - 0.1, y, z - 0.1, x + 0.1, y2, z + 0.1);
		return !level.getBlockCollisions(null, box).iterator().hasNext();
	}

	private static @Nullable TextureAtlas atlasSeen;

	private static @Nullable TextureAtlasSprite sprite(ModuleState s, int kind, RandomSource random) {
		TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(PARTICLES_ATLAS);
		if (atlas != atlasSeen) {
			// Resources reloaded: leaf colours are worked out again from the new textures.
			atlasSeen = atlas;
			COLORS.clear();
		}
		String name = switch (kind) {
			case FallingLeafParticle.SNOW -> "falling_snow";
			case FallingLeafParticle.CONIFER -> CONIFER_SPRITES[random.nextInt(CONIFER_SPRITES.length)];
			default -> s.bool(FallingLeaves.VANILLA_TEXTURES) ? null : LEAF_SPRITES[random.nextInt(LEAF_SPRITES.length)];
		};
		Identifier id = name == null ? Identifier.withDefaultNamespace("leaf_" + random.nextInt(12))
			: Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, name);
		return atlas.getSprite(id);
	}

	// ---- Leaf colours: the block's texture averaged, times its tint where it stands.

	private static final Map<Identifier, double[]> COLORS = new HashMap<>();
	private static final boolean[] TINTED = new boolean[1];

	private static double[] leafColor(BlockState state, ClientLevel level, BlockPos pos) {
		Identifier sprite = com.flintclient.client.compat.McCompat.mapSprite(state, TINTED);
		boolean tinted = TINTED[0];
		if (sprite == null) {
			return new double[] {1.0, 0.1, 0.6};
		}
		double[] base = COLORS.computeIfAbsent(sprite, Mc263FallingLeaves::average);
		double[] color = base.clone();
		int tint = tinted ? com.flintclient.client.compat.McCompat.mapTint(state, level, pos) : -1;
		if (tint != -1) {
			color[0] *= (tint >> 16 & 255) / 255.0;
			color[1] *= (tint >> 8 & 255) / 255.0;
			color[2] *= (tint & 255) / 255.0;
		}
		return color;
	}

	/** The texture's average colour over its opaque pixels (first frame), 0..1 each. */
	private static double[] average(Identifier sprite) {
		Identifier file = sprite.withPath(path -> "textures/" + path + ".png");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
		if (resource.isEmpty()) {
			return new double[] {1, 1, 1};
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			int w = image.getWidth();
			int h = Math.min(image.getHeight(), w);
			double r = 0;
			double g = 0;
			double b = 0;
			int n = 0;
			for (int py = 0; py < h; py++) {
				for (int px = 0; px < w; px++) {
					int argb = image.getPixel(px, py);
					if (argb >>> 24 != 0) {
						r += argb >> 16 & 255;
						g += argb >> 8 & 255;
						b += argb & 255;
						n++;
					}
				}
			}
			return n == 0 ? new double[] {1, 1, 1} : new double[] {r / n / 255.0, g / n / 255.0, b / n / 255.0};
		} catch (Exception e) {
			return new double[] {1, 1, 1};
		}
	}

	// ---- Wind (Falling Leaves' Wind): calm, windy or stormy with the weather, its strength and heading smoothly noisy.

	private static final Random RNG = new Random();
	private static final float TAU = (float) (Math.PI * 2);

	private enum WindState {
		CALM(0.05F, 0.05F, 0.2F),
		WINDY(0.05F, 0.3F, 0.7F),
		STORMY(0.05F, 0.6F, 1.1F);

		final float a;
		final float b;
		final float c;

		WindState(float min, float likely, float max) {
			this.a = min;
			this.b = max;
			this.c = likely;
		}

		/** A triangular distribution's sample: min..max, most likely {@code c}. */
		float sample() {
			float f = (c - a) / (b - a);
			float u = RNG.nextFloat();
			return u < f ? a + (float) Math.sqrt(u * (b - a) * (c - a)) : b - (float) Math.sqrt((1 - u) * (b - a) * (b - c));
		}
	}

	/** Values that move from one random target to the next over a fixed number of ticks. */
	private static final class SmoothNoise {
		private final int interval;
		private final java.util.function.DoubleUnaryOperator next;
		private float left;
		private float right;
		private int ticks;
		private float t;

		SmoothNoise(int interval, float initial, java.util.function.DoubleUnaryOperator next) {
			this.interval = interval;
			this.next = next;
			this.left = initial;
			this.right = (float) next.applyAsDouble(initial);
		}

		void tick() {
			if (++ticks == interval) {
				ticks = 0;
				left = right;
				right = (float) next.applyAsDouble(left);
			}
			t = (float) ticks / interval;
		}

		float lerp() {
			return left + t * (right - left);
		}

		float noise() {
			return left + t * t * (3 - 2 * t) * (right - left);
		}
	}

	private static WindState windState = WindState.CALM;
	private static @Nullable WindState originalState;
	private static int stateDuration;
	private static boolean wasRaining;
	private static boolean wasThundering;
	private static final SmoothNoise VELOCITY = new SmoothNoise(40, 0, old -> windState.sample());
	private static final SmoothNoise TREND = new SmoothNoise(36000, RNG.nextFloat() * TAU, old -> RNG.nextFloat() * TAU);
	private static final SmoothNoise DIRECTION = new SmoothNoise(200, 0, old -> (2 * RNG.nextFloat() - 1) * TAU / 8);

	/** From the client tick while a world runs (Falling Leaves ticks with the particles): the wind. */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.isPaused() || !on()) {
			return;
		}
		try {
			ModuleState s = Mods.state(MODULE);
			refresh(s);
			stateDuration--;
			String dimension = level.dimension().identifier().toString();
			if (s.bool(FallingLeaves.WIND) && !windless.contains(dimension)) {
				if (originalState != null) {
					windState = originalState;
					originalState = null;
				}
				boolean raining = level.isRaining();
				boolean thundering = level.isThundering();
				if (wasRaining != raining || wasThundering != thundering || stateDuration <= 0) {
					windState = thundering ? WindState.STORMY : WindState.values()[RNG.nextInt(2) + (raining ? 1 : 0)];
					stateDuration = 7200;
				}
				wasRaining = raining;
				wasThundering = thundering;
			} else {
				originalState = windState;
				windState = WindState.CALM;
			}
			VELOCITY.tick();
			TREND.tick();
			DIRECTION.tick();
			float strength = VELOCITY.noise();
			float direction = TREND.lerp() + DIRECTION.noise();
			windX = strength * Mth.cos(direction);
			windZ = strength * Mth.sin(direction);
		} catch (RuntimeException | LinkageError ex) {
			GUARD.fail(ex);
		}
	}

	/** Minecraft's own falling-leaf particle of a leaf block (a mixin gives leaf blocks this). */
	public interface VanillaLeaves {
		void flint$spawnVanillaLeaf(Level level, BlockPos pos, RandomSource random);
	}
}
