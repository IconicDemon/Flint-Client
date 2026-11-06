package com.flintclient.client.control.platform.ambient;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The air around you, as the surroundings scan found it: how much open air and sky, how bright the walls are, and which
 * block features (trees, rock, water, sand...) are about and how much. Adapted from AmbientSounds' {@code AirPocket} and
 * {@code AirPocketScanner} (LGPL-3.0, CreativeMD).
 */
final class AirPocket {
	final Map<String, Double> features = new HashMap<>();
	final double averageLight;
	final double averageBlockLight;
	final double averageSkyLight;
	final double air;
	final double sky;

	AirPocket() {
		averageLight = 15;
		averageBlockLight = 15;
		averageSkyLight = 15;
		air = 1;
		sky = 1;
	}

	AirPocket(AmbientEngine engine, HashMap<String, AmbientBlocks.Distribution> distribution, double averageLight, double averageBlockLight,
		double averageSkyLight, double air, int sky) {
		this.averageLight = averageLight;
		this.averageBlockLight = averageBlockLight;
		this.averageSkyLight = averageSkyLight;
		this.air = air < engine.airMin ? 0 : air > engine.airMax ? 1 : (air - engine.airMin) / (engine.airMax - engine.airMin);
		this.sky = sky < engine.skyMinCount ? 0 : sky > engine.skyMaxCount ? 1
			: (double) (sky - engine.skyMinCount) / (engine.skyMaxCount - engine.skyMinCount);
		for (AmbientBlocks.Feature feature : engine.features.values()) {
			double volume = feature.volume(distribution);
			if (volume > 0) {
				features.put(feature.name, volume);
			}
		}
	}

	/** The loudest of {@code names} found around you (0: none). */
	double volume(String[] names) {
		double volume = 0;
		for (String name : names) {
			volume = Math.max(volume, features.getOrDefault(name, 0.0));
		}
		return volume;
	}

	/**
	 * The scan: from your eyes outward, block by block through anything sound passes (air, plants, non-full blocks that
	 * aren't "solid" for the engine), one distance step at a time, only ever moving away from you. It counts what it finds,
	 * weighted by distance, and the light on the walls it reaches. It runs on a thread of its own and rests 1 ms every
	 * {@code steps} blocks (the "scan step amount" setting).
	 */
	static final class Scanner extends Thread {
		private static final Direction[] DIRECTIONS = Direction.values();
		private final AmbientEngine engine;
		private final Level level;
		private final BlockPos origin;
		private final int steps;
		private final Consumer<AirPocket> consumer;
		private final List<HashMap<BlockPos, Inspection>> toScan = new ArrayList<>();
		private final IdentityHashMap<BlockState, Double> foundCount = new IdentityHashMap<>();
		private final LongOpenHashSet sky = new LongOpenHashSet();
		private final LongOpenHashSet skyTested = new LongOpenHashSet();
		private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
		private int skyCounter;
		private double distributionCounter;
		private int totalSize;
		private int lightValueCounter;
		private int blockLightValueCounter;
		private int skyLightValueCounter;
		private int faceCounter;
		private int air;
		private volatile boolean cancelled;

		Scanner(AmbientEngine engine, Level level, BlockPos origin, int steps, Consumer<AirPocket> consumer) {
			super("Flint Ambient Sounds scan");
			setDaemon(true);
			setPriority(Thread.MIN_PRIORITY);
			this.engine = engine;
			this.level = level;
			this.origin = origin;
			this.steps = Math.max(1, steps);
			this.consumer = consumer;
			start();
		}

		void cancel() {
			cancelled = true;
		}

		@Override
		public void run() {
			try {
				HashMap<BlockPos, Inspection> first = new HashMap<>();
				Inspection start = new Inspection(origin);
				first.put(start, start);
				toScan.add(first);
				int counted = 0;
				for (int distance = 0; distance < toScan.size(); distance++) {
					for (Inspection pos : List.copyOf(toScan.get(distance).values())) {
						if (cancelled) {
							return;
						}
						scan(distance, pos);
						if (++counted > steps) {
							counted = 0;
							sleep(1);
						}
					}
				}
				HashMap<String, AmbientBlocks.Distribution> distribution = new HashMap<>();
				for (Map.Entry<String, AmbientBlocks.Group> group : engine.blockGroups.entrySet()) {
					AmbientBlocks.Distribution dist = new AmbientBlocks.Distribution();
					for (Map.Entry<BlockState, Double> state : foundCount.entrySet()) {
						if (group.getValue().is(state.getKey())) {
							dist.add(state.getValue());
						}
					}
					dist.calculatePercentage(distributionCounter);
					distribution.put(group.getKey(), dist);
				}
				if (!cancelled) {
					consumer.accept(new AirPocket(engine, distribution, (double) lightValueCounter / faceCounter, (double) blockLightValueCounter / faceCounter,
						(double) skyLightValueCounter / faceCounter, (double) air / engine.maxAirPocketCount, skyCounter));
				}
			} catch (InterruptedException e) {
				// stopped with the world
			} catch (RuntimeException | LinkageError e) {
				// The world changed under the scan (a chunk unloaded): the next scan starts afresh.
				consumer.accept(null);
			}
		}

		private HashMap<BlockPos, Inspection> getOrCreate(int distance) {
			if (distance >= toScan.size()) {
				HashMap<BlockPos, Inspection> set = new HashMap<>();
				toScan.add(set);
				return set;
			}
			return toScan.get(distance);
		}

		private void findState(BlockState state, int distance) {
			double factor = engine.airWeightFactor(distance);
			distributionCounter += factor;
			foundCount.merge(state, factor, Double::sum);
		}

		private boolean hearThrough(LevelReader reader, BlockState state, BlockPos pos) {
			return state.isAir() || !state.isCollisionShapeFullBlock(reader, pos) && !engine.considerSolid.is(state);
		}

		private static long key(int x, int z) {
			return (long) x << 32 | z & 0xFFFFFFFFL;
		}

		private void scan(int distance, Inspection pos) {
			BlockState state = level.getBlockState(pos);
			long column = key(pos.getX(), pos.getZ());
			if (!skyTested.contains(column) && pos.up) {
				skyTested.add(column);
				if (canSeeSkyConsiderSolids(pos)) {
					sky.add(column);
					if (distance < engine.skyDistance) {
						skyCounter = (int) (skyCounter + (engine.skyDistance - distance));
					}
					if (distance < engine.airSkyDistance) {
						air = (int) (air + (1 - distance / engine.airSkyDistance) * engine.airSkyWeight);
					}
				}
			}
			if (hearThrough(level, state, pos)) {
				if (!state.isAir()) {
					findState(state, distance);
				}
				if (distance < engine.airDistance) {
					air++;
				}
				distance++;
				int x = pos.getX();
				int z = pos.getZ();
				if (sky.contains(column) && sky.contains(key(x - 1, z)) && sky.contains(key(x, z - 1)) && sky.contains(key(x + 1, z))
					&& sky.contains(key(x, z + 1))) {
					return;
				}
				for (Direction direction : DIRECTIONS) {
					if (totalSize > engine.airPocketCount) {
						return;
					}
					Direction.Axis axis = direction.getAxis();
					boolean away = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? pos.get(axis) + 1 > origin.get(axis)
						: pos.get(axis) - 1 < origin.get(axis);
					if (away) {
						HashMap<BlockPos, Inspection> map = getOrCreate(distance);
						BlockPos next = pos.relative(direction);
						Inspection ins = map.get(next);
						if (ins != null) {
							ins.add(direction.getOpposite());
						} else {
							ins = new Inspection(next, direction.getOpposite());
							map.put(ins, ins);
						}
						totalSize++;
					}
				}
			} else {
				for (Direction direction : DIRECTIONS) {
					if (!pos.is(direction)) {
						continue;
					}
					BlockPos other = pos.relative(direction);
					int blockLight = level.getBrightness(LightLayer.BLOCK, other);
					int skyLight = level.getBrightness(LightLayer.SKY, other);
					lightValueCounter += Math.max(blockLight, skyLight);
					blockLightValueCounter += blockLight;
					skyLightValueCounter += skyLight;
					faceCounter++;
				}
				findState(state, distance);
			}
		}

		private boolean canSeeSkyConsiderSolids(BlockPos pos) {
			mutable.set(pos.getX(), pos.getY() + 1, pos.getZ());
			if (!level.canSeeSky(mutable)) {
				return false;
			}
			int maxHeight = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
			for (int y = mutable.getY(); y <= maxHeight; y++) {
				mutable.setY(y);
				if (!hearThrough(level, level.getBlockState(mutable), mutable)) {
					return false;
				}
			}
			return true;
		}
	}

	/** A block to scan, and the sides of it the scan came from. */
	static final class Inspection extends BlockPos {
		boolean east;
		boolean west;
		boolean up;
		boolean down;
		boolean south;
		boolean north;

		Inspection(BlockPos pos) {
			super(pos.getX(), pos.getY(), pos.getZ());
			east = west = up = down = south = north = true;
		}

		Inspection(BlockPos pos, Direction direction) {
			super(pos.getX(), pos.getY(), pos.getZ());
			add(direction);
		}

		void add(Direction direction) {
			switch (direction) {
				case DOWN -> down = true;
				case EAST -> east = true;
				case NORTH -> north = true;
				case SOUTH -> south = true;
				case UP -> up = true;
				case WEST -> west = true;
			}
		}

		boolean is(Direction direction) {
			return switch (direction) {
				case DOWN -> down;
				case EAST -> east;
				case NORTH -> north;
				case SOUTH -> south;
				case UP -> up;
				case WEST -> west;
			};
		}
	}
}
