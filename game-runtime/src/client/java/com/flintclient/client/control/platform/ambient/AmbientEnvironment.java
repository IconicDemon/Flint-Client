package com.flintclient.client.control.platform.ambient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Everything the engine knows about where you are: dimension, time, weather, height above the ground around you,
 * depth under water, temperature, the biomes around, the air pocket and the entities near you. The quick parts are read
 * every few ticks, the slow ones (ground height, biomes, the air scan) every two seconds. Adapted from AmbientSounds'
 * {@code AmbientEnvironment}, {@code TerrainEnvironment} and {@code EntityEnvironment} (LGPL-3.0, CreativeMD).
 */
final class AmbientEnvironment {
	private static final Predicate<BlockState> POSSIBLE_HEIGHT_BLOCK = state -> state.canOcclude() || state.is(BlockTags.LEAVES)
		|| state.getFluidState().is(FluidTags.WATER);
	AmbientDimension dimension;
	boolean muted;
	boolean night;
	double sunAngle;
	double dayTimeHour;
	double rainSurfaceVolume;
	boolean raining;
	boolean snowing;
	boolean thundering;
	BiomeEnvironment biome = new BiomeEnvironment();
	final Terrain terrain = new Terrain();
	final Entities entity = new Entities();
	AmbientVolume biomeVolume = AmbientVolume.SILENT;
	final HashMap<String, AmbientVolume> biomeTypeVolumes = new HashMap<>();
	double absoluteHeight;
	double relativeHeight;
	double relativeMinHeight;
	double relativeMaxHeight;
	double underwater;
	double temperature;

	boolean isRainAudibleAtSurface() {
		return rainSurfaceVolume > 0;
	}

	void analyzeFast(AmbientEngine engine, AmbientDimension dimension, Player player, Level level, float deltaTime) {
		this.dimension = dimension;
		raining = level.isRainingAt(player.blockPosition().above());
		snowing = level.getBiome(player.blockPosition()).value().coldEnoughToSnow(player.blockPosition(), level.getSeaLevel()) && level.isRaining();
		thundering = level.isThundering() && !snowing;
		absoluteHeight = player.getEyeY();
		relativeHeight = absoluteHeight - terrain.averageHeight;
		relativeMinHeight = absoluteHeight - terrain.maxHeight;
		relativeMaxHeight = absoluteHeight - terrain.minHeight;
		// Serene Seasons' seasonal temperature isn't available to Flint: the biome's own, as the mod uses without it.
		temperature = level.getBiome(player.blockPosition()).value().getBaseTemperature();
		analyzeUnderwater(engine, player, level);
		analyzeTime(level, player);
		entity.analyzeFast(player, level, deltaTime);
	}

	void analyzeTime(Level level, Player player) {
		sunAngle = AmbientCompat.sunAngle(level, player) - 180;
		if (sunAngle < 0) {
			sunAngle += 360;
		}
		night = sunAngle < 90 || sunAngle > 270;
		dayTimeHour = sunAngle * AmbientTime.ANGLE_TO_TIME;
	}

	private static boolean isWater(FluidState fluid, AmbientEngine engine) {
		for (TagKey<Fluid> tag : engine.considerWater) {
			if (fluid.is(tag)) {
				return true;
			}
		}
		return false;
	}

	void analyzeUnderwater(AmbientEngine engine, Player player, Level level) {
		int depth = 0;
		boolean eyeIn = false;
		for (TagKey<Fluid> fluid : engine.considerWater) {
			if (player.isEyeInFluid(fluid)) {
				eyeIn = true;
				break;
			}
		}
		if (eyeIn) {
			for (BlockPos pos = BlockPos.containing(player.getEyePosition()); isWater(level.getFluidState(pos), engine); pos = pos.above()) {
				depth++;
			}
		}
		underwater = depth;
	}

	void analyzeSlow(AmbientEngine engine, AmbientDimension dimension, Player player, Level level, int scanSteps) {
		terrain.analyze(engine, dimension, player, level, scanSteps);
		biome = new BiomeEnvironment(engine, player, level, biomeVolume);
		rainSurfaceVolume = biome.rainVolume();
	}

	/** The ground around you and the air pocket you're in. */
	static final class Terrain {
		double averageHeight = 60;
		int minHeight = 60;
		int maxHeight = 60;
		volatile AirPocket airPocket = new AirPocket();
		volatile AirPocket.@Nullable Scanner scanner;

		void analyze(AmbientEngine engine, AmbientDimension dimension, Player player, Level level, int scanSteps) {
			analyzeHeight(engine, dimension, player, level);
			if (scanner == null) {
				scanner = new AirPocket.Scanner(engine, level, BlockPos.containing(player.getEyePosition(1)), scanSteps, pocket -> {
					if (pocket != null) {
						airPocket = pocket;
					}
					scanner = null;
				});
			}
		}

		void stopScan() {
			AirPocket.Scanner running = scanner;
			if (running != null) {
				running.cancel();
				running.interrupt();
				scanner = null;
			}
		}

		void analyzeHeight(AmbientEngine engine, AmbientDimension dimension, Player player, Level level) {
			if (dimension.averageHeight != null) {
				averageHeight = dimension.averageHeight;
				minHeight = dimension.averageHeight;
				maxHeight = dimension.averageHeight;
				return;
			}
			int sum = 0;
			int count = 0;
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			BlockPos center = player.blockPosition();
			for (int x = -engine.averageHeightScanCount; x <= engine.averageHeightScanCount; x++) {
				for (int z = -engine.averageHeightScanCount; z <= engine.averageHeightScanCount; z++) {
					pos.set(center.getX() + engine.averageHeightScanDistance * x, center.getY(), center.getZ() + engine.averageHeightScanDistance * z);
					int height = heightBlock(level, pos);
					min = Math.min(height, min);
					max = Math.max(height, max);
					sum += height;
					count++;
				}
			}
			averageHeight = (double) sum / count;
			minHeight = min;
			maxHeight = max;
		}

		private static int heightBlock(Level level, BlockPos.MutableBlockPos pos) {
			for (int y = heightScanStart(level, pos); y > level.getMinY(); y--) {
				pos.setY(y);
				BlockState state = level.getBlockState(pos);
				if (state.isSolidRender() || state.is(BlockTags.LEAVES) || level.getFluidState(pos).is(FluidTags.WATER)) {
					return y;
				}
			}
			return 0;
		}

		private static int heightScanStart(Level level, BlockPos.MutableBlockPos pos) {
			int chunkX = SectionPos.blockToSectionCoord(pos.getX());
			int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
			if (level.getClass() == ClientLevel.class && level.hasChunk(chunkX, chunkZ) && !POSSIBLE_HEIGHT_BLOCK.test(Blocks.VOID_AIR.defaultBlockState())
				&& !POSSIBLE_HEIGHT_BLOCK.test(Blocks.AIR.defaultBlockState())) {
				LevelChunkSection[] sections = level.getChunk(chunkX, chunkZ).getSections();
				for (int i = sections.length - 1; i >= 0; i--) {
					if (sections[i] != null && sections[i].maybeHas(POSSIBLE_HEIGHT_BLOCK)) {
						return SectionPos.sectionToBlockCoord(level.getMinSectionY() + i, 15);
					}
				}
				return level.getMinY() + 1;
			}
			return level.getMaxY();
		}
	}

	/** The entities around you (for entity conditions). */
	static final class Entities {
		private final List<Entity> entities = new ArrayList<>();
		private Vec3 position = Vec3.ZERO;

		void analyzeFast(Player player, Level level, float deltaTime) {
			position = player.getEyePosition(deltaTime);
			entities.clear();
			for (Entity e : ((ClientLevel) level).entitiesForRendering()) {
				entities.add(e);
			}
		}

		double squaredDistance(Entity entity) {
			return entity.distanceToSqr(position);
		}

		double x() {
			return position.x;
		}

		double y() {
			return position.y;
		}

		double z() {
			return position.z;
		}

		Iterable<Entity> all() {
			return entities;
		}
	}
}
