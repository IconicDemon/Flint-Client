package com.flintclient.client.control.platform.ambient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * The biomes around you: a grid of points, each biome counted once at its nearest point's closeness (1 where you stand,
 * less farther out), loudest first; and how much rain falls on them. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
final class BiomeEnvironment implements Iterable<BiomeEnvironment.Entry> {
	record Entry(BiomeArea area, AmbientVolume volume) {
	}

	private final List<Entry> biomes = new ArrayList<>();
	private double highestRainVolume;

	BiomeEnvironment() {
	}

	BiomeEnvironment(AmbientEngine engine, Player player, Level level, AmbientVolume volume) {
		if (volume.volume() <= 0) {
			return;
		}
		BlockPos center = BlockPos.containing(player.getEyePosition(1));
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = -engine.biomeScanCount; x <= engine.biomeScanCount; x++) {
			for (int z = -engine.biomeScanCount; z <= engine.biomeScanCount; z++) {
				pos.set(center.getX() + x * engine.biomeScanDistance, center.getY(), center.getZ() + z * engine.biomeScanDistance);
				Holder<Biome> holder = level.getBiome(pos);
				float biomeConditionVolume = (float) ((1 - center.distSqr(pos) / engine.squaredBiomeDistance) * volume.conditionVolume());
				if (level.isRaining() && holder.value().getPrecipitationAt(pos, level.getSeaLevel()) == Biome.Precipitation.RAIN) {
					highestRainVolume = Math.max(highestRainVolume, biomeConditionVolume * volume.settingVolume());
				}
				Identifier id = holder.unwrapKey().map(AmbientCompat::id).orElse(null);
				if (id == null) {
					continue;
				}
				Entry before = null;
				for (Entry entry : biomes) {
					if (entry.area().biome().equals(holder)) {
						before = entry;
						break;
					}
				}
				if (before == null) {
					biomes.add(new Entry(new BiomeArea(holder, id, pos.immutable()), new AmbientVolume(biomeConditionVolume, volume.settingVolume())));
				} else if (before.volume().conditionVolume() < biomeConditionVolume) {
					before.volume().setConditionVolumeDirect(biomeConditionVolume);
				}
			}
		}
		biomes.sort(Comparator.comparingDouble((Entry e) -> e.volume().volume()).reversed());
	}

	@Override
	public Iterator<Entry> iterator() {
		return biomes.iterator();
	}

	double rainVolume() {
		return highestRainVolume;
	}

	List<Entry> list() {
		return biomes;
	}

	record BiomeArea(Holder<Biome> biome, Identifier biomeId, BlockPos pos) {
		boolean checkBiome(BiomeCondition[] conditions) {
			for (BiomeCondition condition : conditions) {
				if (condition.tag()) {
					if (biome.tags().anyMatch(tag -> condition.matches(AmbientCompat.id(tag)))) {
						return true;
					}
				} else if (condition.matches(biomeId)) {
					return true;
				}
			}
			return false;
		}
	}
}
