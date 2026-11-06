package com.flintclient.client.control.platform.sound;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * The world as the sound thread sees it: the chunks around you, copied on the game thread every so often (Sound Physics
 * Remastered's level clone). The copy holds each chunk's section list, so making it is cheap and reading it never touches
 * the client's chunk storage, which the game thread changes as chunks load and unload. Blocks outside it read as void air.
 *
 * <p>Adapted from Sound Physics Remastered's {@code ClonedClientLevel} (GPL-3.0; see
 * META-INF/licenses/GPL-3.0-SoundPhysicsRemastered.txt).
 */
final class SoundLevel implements BlockGetter {
	private final Long2ObjectOpenHashMap<LevelChunkSection[]> chunks;
	private final int minY;
	private final int height;
	private final int minSection;
	final BlockPos origin;
	final long tick;

	private SoundLevel(Long2ObjectOpenHashMap<LevelChunkSection[]> chunks, int minY, int height, BlockPos origin, long tick) {
		this.chunks = chunks;
		this.minY = minY;
		this.height = height;
		this.minSection = minY >> 4;
		this.origin = origin;
		this.tick = tick;
	}

	/** The chunks from {@code range} chunks west/north of {@code origin}'s chunk to {@code range - 1} east/south, as the mod copies. */
	static SoundLevel copy(ClientLevel level, BlockPos origin, long tick, int range) {
		Long2ObjectOpenHashMap<LevelChunkSection[]> chunks = new Long2ObjectOpenHashMap<>(4 * range * range);
		int cx = origin.getX() >> 4;
		int cz = origin.getZ() >> 4;
		for (int x = -range; x < range; x++) {
			for (int z = -range; z < range; z++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx + x, cz + z, false);
				if (chunk != null) {
					chunks.put(key(cx + x, cz + z), chunk.getSections().clone());
				}
			}
		}
		return new SoundLevel(chunks, level.getMinY(), level.getHeight(), origin, tick);
	}

	private static long key(int chunkX, int chunkZ) {
		return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
	}

	private @Nullable LevelChunkSection section(BlockPos pos) {
		int y = pos.getY();
		if (y < minY || y >= minY + height) {
			return null;
		}
		LevelChunkSection[] sections = chunks.get(key(pos.getX() >> 4, pos.getZ() >> 4));
		if (sections == null) {
			return null;
		}
		int index = (y >> 4) - minSection;
		return index >= 0 && index < sections.length ? sections[index] : null;
	}

	private boolean loaded(BlockPos pos) {
		return chunks.containsKey(key(pos.getX() >> 4, pos.getZ() >> 4));
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		LevelChunkSection section = section(pos);
		if (section == null) {
			int y = pos.getY();
			return y < minY || y >= minY + height || !loaded(pos) ? Blocks.VOID_AIR.defaultBlockState() : Blocks.AIR.defaultBlockState();
		}
		return section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		LevelChunkSection section = section(pos);
		return section == null || section.hasOnlyAir() ? Fluids.EMPTY.defaultFluidState()
			: section.getFluidState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
	}

	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public int getHeight() {
		return height;
	}

	@Override
	public int getMinY() {
		return minY;
	}
}
