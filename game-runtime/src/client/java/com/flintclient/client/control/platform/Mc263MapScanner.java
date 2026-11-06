package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.map.MapRegion;
import com.flintclient.client.control.feature.map.MapShading;
import com.flintclient.client.control.feature.map.MapWorld;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

/**
 * Reads loaded chunks into the map: per column, what you'd see looking straight down, its height and biome, read the way
 * Xaero's maps read the world.
 *
 * <ul>
 * <li><b>Colours.</b> "Textures": the average colour of the block's top texture (read once per texture from the resource
 * packs, so packs change the map), times the biome's tint where the face takes one (grass and leaves as Minecraft tints
 * them, water by the biome's water colour), blended with the biomes two blocks round it so borders are soft. "Map
 * colors": the colour Minecraft's own maps use for the block.</li>
 * <li><b>What's left out.</b> Air, invisible blocks, torches, grass and tall grass, plain glass (Xaero's list), and flowers
 * when they are turned off.</li>
 * <li><b>See-through layers.</b> Water, ice and stained glass are layers over the ground below them ({@link
 * MapShading#overlay}): their colour at the texture's own opacity, and every block of water or ice takes a level of
 * sunlight from what is under it, so shallows show their sand and deep sea is dark blue. The floor is found however
 * deep.</li>
 * <li><b>Glowing blocks</b> (lava, glowstone, any block giving light) are brightened as Xaero's and shaded as light
 * sources.</li>
 * <li><b>The void.</b> A column with nothing down to the bottom (the End's void) is Xaero's deep violet.</li>
 * <li><b>Ceilings and caves.</b> A dimension with a roof (the Nether) and a cave layer are read from a starting height
 * down: rock at the start is passed until the first open space, and the floor below it is the map. A cave layer's floor
 * is as bright as the light on it ((9 + light) / 24, the Nether never under its dim glow), so caves are dark and the
 * places you lit stand out; rock all the way down is black.</li>
 * </ul>
 * Each block state's kind and texture colour are worked out once and kept. Client thread; a chunk takes well under a
 * millisecond (empty sections are skipped whole).
 */
final class Mc263MapScanner {
	static final int SKIP = 0;
	static final int SOLID = 1;
	static final int FLOWER = 2;
	static final int GLASS = 3;
	static final int WATER = 4;
	static final int ICE = 5;
	static final int LAVA = 6;

	/**
	 * What a block state is for the map: its kind, texture colour, opacity as a layer (0..1), whether its face is tinted, and
	 * whether it glows.
	 */
	private record Info(int kind, int rgb, float alpha, boolean tinted, boolean glows) {
	}

	private static final Map<BlockState, Info> INFO = new IdentityHashMap<>();
	private static final Map<Identifier, Integer> SPRITES = new HashMap<>();
	private static final Map<Holder<Biome>, String> BIOME_NAMES = new IdentityHashMap<>();
	private static final Map<Holder<Biome>, Map<BlockState, Integer>> TINTS = new IdentityHashMap<>();
	/** The biome blend's samples: the column, and two blocks out each way. */
	private static final int[] TAPS = {0, 0, 2, 0, -2, 0, 0, 2, 0, -2};
	/** The chunk being read. */
	private static LevelChunk chunkNow;
	private static final boolean[] TINTED = new boolean[1];
	private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
	private static final int[] COLORS = new int[256];
	private static final short[] HEIGHTS = new short[256];
	private static final byte[] BIOMES = new byte[256];
	private static final float[] ACC = new float[4];
	/** The scan in progress is a cave layer: its floors are lit by the light on them. */
	private static boolean lit;
	/** The least light a cave layer's floor has (the Nether's dim glow; none under the Overworld's sky). */
	private static int leastLight;

	/** What the scan shows (from the World Map's look settings). */
	record Options(int colors, boolean seeWater, boolean plants, boolean glass) {
	}

	private Mc263MapScanner() {
	}

	/** Forgets every block's colour (resource packs changed, or the colour source did). */
	static void forgetColors() {
		INFO.clear();
		SPRITES.clear();
		BIOME_NAMES.clear();
		TINTS.clear();
	}

	/**
	 * Reads chunk {@code chunk} into {@code world}: from its surface down ({@code startY} = Integer.MIN_VALUE), or as a cave
	 * layer / roofed dimension from {@code startY} down at most {@code depth} blocks ({@code cave}: a cave layer, lit by the
	 * light on its floors). Returns whether the map changed.
	 */
	static boolean scan(MapWorld world, ClientLevel level, LevelChunk chunk, Options options, int startY, int depth, boolean cave) {
		lit = cave;
		leastLight = level.dimensionType().hasSkyLight() ? 0 : 2;
		chunkNow = chunk;
		int cx = chunk.getPos().getMinBlockX() >> 4;
		int cz = chunk.getPos().getMinBlockZ() >> 4;
		int minY = chunk.getMinY();
		int maxY = minY + chunk.getHeight() - 1;
		LevelChunkSection[] sections = chunk.getSections();
		boolean fromStart = startY != Integer.MIN_VALUE;
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int top;
				int floor;
				if (fromStart) {
					top = Math.min(startY, maxY);
					floor = Math.max(minY, top - depth);
				} else {
					top = Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz));
					floor = minY;
				}
				column(level, chunk, sections, minY, (cx << 4) + lx, (cz << 4) + lz, top, floor, fromStart, options, lz << 4 | lx, world);
			}
		}
		MapRegion region = world.forWrite(cx >> 5, cz >> 5);
		boolean changed = region.writeChunk(cx & 31, cz & 31, COLORS, HEIGHTS, BIOMES);
		if (changed) {
			world.changed(region);
		}
		return changed;
	}

	private static BlockState state(LevelChunkSection[] sections, int minY, int x, int y, int z) {
		int index = (y - minY) >> 4;
		if (index < 0 || index >= sections.length) {
			return Blocks.AIR.defaultBlockState();
		}
		return sections[index].getBlockState(x & 15, y & 15, z & 15);
	}

	/**
	 * Whether a block roofs you for the auto cave mode (Xaero's test): something solid to stand on, not water, leaves, glass,
	 * ice or a barrier.
	 */
	static boolean roof(BlockState state) {
		return !state.isAir() && info(state).kind == SOLID && !state.is(BlockTags.LEAVES) && state.getBlock() != Blocks.BARRIER
			&& !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	/** Whether a block counts as open space when a cave layer or a roof is passed (not rock). */
	private static boolean open(Info info) {
		return info.kind != SOLID;
	}

	private static void column(ClientLevel level, LevelChunk chunk, LevelChunkSection[] sections, int minY, int x, int z, int top, int floor,
		boolean fromStart, Options o, int at, MapWorld world) {
		int y = top;
		if (fromStart) {
			// Rock at the start: pass it until the first open space (none within the depth: solid rock, shown black).
			// A roofed dimension's map skips pockets in its roof: the open space must be a few blocks tall.
			int gap = lit ? 1 : 4;
			while (y >= floor) {
				while (y >= floor && !open(info(state(sections, minY, x, y, z)))) {
					y--;
				}
				int space = 0;
				while (y - space >= floor && space < gap && open(info(state(sections, minY, x, y - space, z)))) {
					space++;
				}
				if (space >= gap || y - space < floor) {
					break;
				}
				y -= space;
			}
			if (y < floor) {
				COLORS[at] = MapShading.pack(MapRegion.LAND, MapShading.ROCK_RGB);
				HEIGHTS[at] = (short) top;
				BIOMES[at] = 0;
				return;
			}
		}
		float[] acc = ACC;
		acc[0] = 0;
		acc[1] = 0;
		acc[2] = 0;
		acc[3] = 1;
		int sun = 15;
		int layerKind = SKIP;
		BlockState layerState = null;
		int waterTop = Integer.MIN_VALUE;
		int rgb = -1;
		boolean glows = false;
		int height = floor;
		while (y >= floor) {
			int index = (y - minY) >> 4;
			LevelChunkSection section = index >= 0 && index < sections.length ? sections[index] : null;
			if (section == null || section.hasOnlyAir()) {
				// A whole empty section: down to the one below.
				y = (index << 4) + minY - 1;
				layerKind = SKIP;
				continue;
			}
			BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
			Info info = info(state);
			int kind = info.kind;
			if (kind == FLOWER) {
				kind = o.plants() ? SOLID : SKIP;
			} else if (kind == GLASS && !o.glass()) {
				kind = SKIP;
			}
			if (kind == SKIP) {
				layerKind = SKIP;
				y--;
				continue;
			}
			if (kind == WATER && !o.seeWater()) {
				// Water drawn as a surface: its colour, a little dark, flat.
				int water = color(level, Blocks.WATER.defaultBlockState(), info, x, y, z, o);
				COLORS[at] = MapShading.pack(MapRegion.WATER, MapShading.scale(water, 0.85F));
				HEIGHTS[at] = (short) y;
				BIOMES[at] = (byte) biome(world, chunk, x, y, z);
				return;
			}
			if (kind == WATER || kind == ICE || kind == GLASS) {
				// A see-through layer: a new one where the block changes, else one more block of the same (it only takes
				// sunlight from what's under it).
				BlockState look = kind == WATER ? Blocks.WATER.defaultBlockState() : state;
				if (layerKind != kind || layerState != look) {
					int c = color(level, look, info, x, y, z, o);
					float alpha = o.colors() == 1 ? (kind == WATER ? 191 : kind == ICE ? 216 : 127) / 255.0F : info.alpha;
					MapShading.overlay(acc, c, alpha, sun);
					layerKind = kind;
					layerState = look;
				}
				if (kind == WATER && waterTop == Integer.MIN_VALUE) {
					waterTop = y;
				}
				if (kind != GLASS) {
					sun = Math.max(0, sun - 1);
				}
				y--;
				continue;
			}
			rgb = color(level, state, info, x, y, z, o);
			glows = info.glows;
			height = y;
			break;
		}
		float light = acc[3] < 1 ? MapShading.floorLight(sun) : 1;
		if (glows) {
			rgb = MapShading.glow(rgb);
		}
		int kind;
		if (rgb < 0) {
			if (acc[3] < 1) {
				// Layers down to the bottom: over darkness.
				rgb = 0;
				kind = waterTop != Integer.MIN_VALUE ? MapRegion.WATER : MapRegion.LAND;
			} else if (fromStart) {
				COLORS[at] = MapShading.pack(MapRegion.LAND, MapShading.ROCK_RGB);
				HEIGHTS[at] = (short) top;
				BIOMES[at] = 0;
				return;
			} else {
				// Nothing down to the bottom: the void.
				COLORS[at] = MapShading.pack(MapRegion.VOID, MapShading.VOID_RGB);
				HEIGHTS[at] = (short) minY;
				BIOMES[at] = 0;
				return;
			}
		} else {
			kind = waterTop != Integer.MIN_VALUE ? MapRegion.WATER : glows ? MapRegion.GLOW : MapRegion.LAND;
		}
		int shown = MapShading.under(acc, rgb, light);
		if (lit && !glows) {
			// Underground, as bright as the light on the floor (and on the water over it).
			int at1 = (waterTop != Integer.MIN_VALUE ? waterTop : height) + 1;
			POS.set(x, at1, z);
			int l = Math.max(leastLight, Math.max(level.getBrightness(LightLayer.BLOCK, POS), level.getBrightness(LightLayer.SKY, POS)));
			shown = MapShading.scale(shown, MapShading.floorLight(l));
		}
		COLORS[at] = MapShading.pack(kind, shown);
		HEIGHTS[at] = (short) (waterTop != Integer.MIN_VALUE ? waterTop : height);
		BIOMES[at] = (byte) biome(world, chunk, x, height, z);
	}

	private static Holder<Biome> noiseBiome(ClientLevel level, int x, int y, int z) {
		LevelChunk chunk = chunkNow;
		int qy = Math.clamp(y, chunk.getMinY(), chunk.getMinY() + chunk.getHeight() - 1) >> 2;
		if (x >> 4 == chunk.getPos().getMinBlockX() >> 4 && z >> 4 == chunk.getPos().getMinBlockZ() >> 4) {
			return chunk.getNoiseBiome(x >> 2, qy, z >> 2);
		}
		return level.getNoiseBiome(x >> 2, qy, z >> 2);
	}

	private static int biome(MapWorld world, LevelChunk chunk, int x, int y, int z) {
		Holder<Biome> holder = chunk.getNoiseBiome(x >> 2, Math.clamp(y, chunk.getMinY(), chunk.getMinY() + chunk.getHeight() - 1) >> 2, z >> 2);
		String name = BIOME_NAMES.get(holder);
		if (name == null) {
			name = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("");
			BIOME_NAMES.put(holder, name);
		}
		return name.isEmpty() ? 0 : world.biome(name);
	}

	private static int color(ClientLevel level, BlockState state, Info info, int x, int y, int z, Options o) {
		if (o.colors() == 1) {
			MapColor map = state.getMapColor(level, POS.set(x, y, z));
			return map == MapColor.NONE ? info.rgb : map.col;
		}
		if (!info.tinted) {
			return info.rgb;
		}
		// The biome's tint here and two blocks out each way, averaged (Xaero blends biome colours so borders are soft).
		int r = 0;
		int g = 0;
		int b = 0;
		for (int i = 0; i < TAPS.length; i += 2) {
			int c = tint(level, state, info, x + TAPS[i], y, z + TAPS[i + 1]);
			r += c >> 16 & 255;
			g += c >> 8 & 255;
			b += c & 255;
		}
		int n = TAPS.length / 2;
		return (r / n) << 16 | (g / n) << 8 | b / n;
	}

	/** The block's colour tinted for the biome at ({@code x}, {@code y}, {@code z}): worked out once per biome and block. */
	private static int tint(ClientLevel level, BlockState state, Info info, int x, int y, int z) {
		Holder<Biome> biome = noiseBiome(level, x, y, z);
		Map<BlockState, Integer> perBlock = TINTS.computeIfAbsent(biome, k -> new IdentityHashMap<>());
		Integer known = perBlock.get(state);
		if (known == null) {
			int tint = info.kind == WATER
				// Water's tint comes with its fluid, not its block: the biome's water colour.
				? biome.value().getWaterColor()
				: com.flintclient.client.compat.McCompat.mapTint(state, level, POS.set(x, y, z));
			known = tint == -1 ? info.rgb : MapShading.multiply(info.rgb, tint);
			perBlock.put(state, known);
		}
		return known;
	}

	private static Info info(BlockState state) {
		Info info = INFO.get(state);
		if (info == null) {
			info = classify(state);
			INFO.put(state, info);
		}
		return info;
	}

	private static Info classify(BlockState state) {
		if (state.isAir()) {
			return new Info(SKIP, 0, 0, false, false);
		}
		FluidState fluid = state.getFluidState();
		boolean water = !fluid.isEmpty() && (fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER);
		boolean lava = !fluid.isEmpty() && (fluid.getType() == Fluids.LAVA || fluid.getType() == Fluids.FLOWING_LAVA);
		if (water && (state.getBlock() == Blocks.WATER || !state.isSolidRender() && state.getCollisionShape(EmptyBlockGetter.INSTANCE,
			BlockPos.ZERO).isEmpty())) {
			// Water, and what grows in it (seagrass, kelp): seen as water.
			int argb = texture(Blocks.WATER.defaultBlockState());
			return new Info(WATER, argb & 0xFFFFFF, alpha(argb), true, false);
		}
		if (lava) {
			// Drawn as ground, but open space when a cave layer or a roof is passed (a fluid, as Xaero's reads it).
			return new Info(LAVA, texture(Blocks.LAVA.defaultBlockState()) & 0xFFFFFF, 1, false, true);
		}
		if (state.getRenderShape() == RenderShape.INVISIBLE) {
			return new Info(SKIP, 0, 0, false, false);
		}
		Block block = state.getBlock();
		// Xaero's: torches, grass, tall grass and large ferns, and plain glass never show.
		boolean flower = block instanceof FlowerBlock || block instanceof TallFlowerBlock || block instanceof PitcherCropBlock
			|| state.is(BlockTags.FLOWERS) && !state.is(BlockTags.LEAVES);
		if (block == Blocks.TORCH || block == Blocks.SHORT_GRASS || block == Blocks.GLASS || block == Blocks.GLASS_PANE
			|| block instanceof DoublePlantBlock && !flower) {
			return new Info(SKIP, 0, 0, false, false);
		}
		int argb = texture(state);
		boolean tinted = TINTED[0];
		int rgb = argb & 0xFFFFFF;
		boolean glows = state.getLightEmission() > 0;
		if (block == Blocks.ICE || block == Blocks.FROSTED_ICE) {
			return new Info(ICE, rgb, alpha(argb), tinted, false);
		}
		if (block instanceof StainedGlassBlock || block instanceof StainedGlassPaneBlock) {
			return new Info(GLASS, rgb, alpha(argb), tinted, false);
		}
		if (flower) {
			return new Info(FLOWER, rgb, 1, tinted, glows);
		}
		return new Info(SOLID, rgb, 1, tinted, glows);
	}

	/** A texture's mean opacity (its top byte) as a layer's, 0..1. */
	private static float alpha(int argb) {
		return Math.clamp((argb >>> 24) / 255.0F, 0.05F, 1);
	}

	/**
	 * The block's top texture's average colour (alpha-weighted) with its average opacity in the top byte; {@link #TINTED}
	 * says whether the face is tinted. Grey when it can't be read.
	 */
	private static int texture(BlockState state) {
		Identifier sprite;
		try {
			sprite = com.flintclient.client.compat.McCompat.mapSprite(state, TINTED);
		} catch (RuntimeException | LinkageError e) {
			sprite = null;
			TINTED[0] = false;
		}
		if (sprite == null) {
			MapColor map = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			return 0xFF000000 | (map == MapColor.NONE ? 0x808080 : map.col);
		}
		Integer known = SPRITES.get(sprite);
		if (known != null) {
			return known;
		}
		int value = average(sprite);
		if (value == 0) {
			MapColor map = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			value = 0xFF000000 | (map == MapColor.NONE ? 0x808080 : map.col);
		}
		SPRITES.put(sprite, value);
		return value;
	}

	/** A texture's first frame averaged: colour weighted by alpha, and the mean alpha on top; 0 when unreadable. */
	private static int average(Identifier sprite) {
		Identifier file = sprite.withPath(path -> "textures/" + path + ".png");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
		if (resource.isEmpty()) {
			return 0;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			int w = image.getWidth();
			int h = Math.min(image.getHeight(), w);
			long r = 0;
			long g = 0;
			long b = 0;
			long a = 0;
			for (int py = 0; py < h; py++) {
				for (int px = 0; px < w; px++) {
					int argb = image.getPixel(px, py);
					int alpha = argb >>> 24;
					r += (long) (argb >> 16 & 255) * alpha;
					g += (long) (argb >> 8 & 255) * alpha;
					b += (long) (argb & 255) * alpha;
					a += alpha;
				}
			}
			if (a == 0) {
				return 0x01808080;
			}
			int meanAlpha = (int) (a / ((long) w * h));
			return Math.max(1, meanAlpha) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
		} catch (Exception e) {
			return 0;
		}
	}

	/** For QA: a block state as the map reads it (its kind and colour). */
	static String describe(BlockState state) {
		Info info = info(state);
		return state.getBlock().getDescriptionId() + "/" + info.kind + "/" + Integer.toHexString(info.rgb) + (info.tinted ? "t" : "")
			+ (info.glows ? "g" : "") + (info.alpha < 1 ? "a" + Math.round(info.alpha * 100) : "");
	}

	/** A biome's name to show ("Dark Forest"), from its id ("minecraft:dark_forest") in the language in use. */
	static String biomeTitle(@Nullable String id) {
		if (id == null || id.isEmpty()) {
			return "";
		}
		int colon = id.indexOf(':');
		String key = "biome." + (colon < 0 ? "minecraft" : id.substring(0, colon)) + "." + id.substring(colon + 1).replace('/', '.');
		String text = net.minecraft.network.chat.Component.translatable(key).getString();
		if (!text.equals(key)) {
			return text;
		}
		String path = id.substring(colon + 1).replace('_', ' ');
		return path.isEmpty() ? id : Character.toUpperCase(path.charAt(0)) + path.substring(1);
	}
}
