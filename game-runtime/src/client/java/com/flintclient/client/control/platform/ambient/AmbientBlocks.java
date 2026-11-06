package com.flintclient.client.control.platform.ambient;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Blocks the engine recognises around you: block groups (block ids, "#tags", either with "[property=value]"), and
 * features built from how much of each group the surroundings scan found. Adapted from AmbientSounds' {@code AmbientBlock},
 * {@code AmbientBlockGroup}, {@code AmbientFeature}, {@code AmbientFeatureSelection} and {@code BlockDistribution}
 * (LGPL-3.0, CreativeMD).
 */
final class AmbientBlocks {
	private AmbientBlocks() {
	}

	/** One block entry of a group. */
	abstract static class Matcher {
		abstract boolean is(BlockState state);

		static Matcher parse(String data) {
			if (data.contains("[")) {
				if (!data.endsWith("]")) {
					throw new IllegalArgumentException("Cannot parse block entry " + data);
				}
				String[] parts = data.split("\\[");
				if (parts.length > 2) {
					throw new IllegalArgumentException("Cannot parse block entry " + data);
				}
				Matcher block = first(parts[0]);
				List<String[]> found = new ArrayList<>();
				for (String property : parts[1].substring(0, parts[1].length() - 1).split(",")) {
					String[] pair = property.split("=");
					if (pair.length == 2) {
						found.add(pair);
					}
				}
				return new WithProperties(block, found);
			}
			return first(data);
		}

		private static Matcher first(String data) {
			if (data.startsWith("#")) {
				TagKey<Block> tag = TagKey.create(Registries.BLOCK, Identifier.parse(data.substring(1)));
				return new Matcher() {
					@Override
					boolean is(BlockState state) {
						return state.is(tag);
					}
				};
			}
			Identifier id = Identifier.parse(data);
			return new Matcher() {
				@Override
				boolean is(BlockState state) {
					return BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(id);
				}
			};
		}
	}

	private static final class WithProperties extends Matcher {
		private final Matcher block;
		private final List<String[]> properties;

		WithProperties(Matcher block, List<String[]> properties) {
			this.block = block;
			this.properties = properties;
		}

		@Override
		@SuppressWarnings({"rawtypes", "unchecked"})
		boolean is(BlockState state) {
			if (!block.is(state)) {
				return false;
			}
			for (String[] pair : properties) {
				Property property = state.getBlock().getStateDefinition().getProperty(pair[0]);
				if (property == null) {
					return false;
				}
				Optional value = property.getValue(pair[1]);
				if (value.isEmpty() || !state.getValue(property).equals(value.get())) {
					return false;
				}
			}
			return true;
		}
	}

	/** A named list of blocks. */
	static final class Group {
		private final List<String> data = new ArrayList<>();
		private List<Matcher> filters = List.of();

		void add(String[] entries) {
			data.addAll(Arrays.asList(entries));
		}

		/** Parses the entries (when a world loads: tags and blocks exist by then). */
		void onClientLoad() {
			List<Matcher> parsed = new ArrayList<>();
			for (String entry : data) {
				try {
					parsed.add(Matcher.parse(entry));
				} catch (RuntimeException | LinkageError e) {
					AmbientSounds.LOGGER.error("Failed to load block entry {}", entry, e);
				}
			}
			filters = parsed;
		}

		boolean is(BlockState state) {
			for (Matcher block : filters) {
				if (block.is(state)) {
					return true;
				}
			}
			return false;
		}
	}

	/** How much of a group the scan found: a weighted count, and its share of everything found. */
	static final class Distribution {
		double percentage;
		double count;

		void add(double count) {
			this.count += count;
		}

		void calculatePercentage(double total) {
			percentage = count / total;
		}

		void add(Distribution other) {
			percentage += other.percentage;
			count += other.count;
		}
	}

	/** A feature's rule: enough of some groups (by count, by share), and/or/not other rules. */
	static class Selection {
		public String[] groups;
		public Selection[] and;
		public Selection[] or;
		public Selection[] not;
		@SerializedName("low-weight")
		public Double lowWeight;
		@SerializedName("high-weight")
		public Double highWeight;
		@SerializedName("low-count")
		public Double lowCount;
		@SerializedName("high-count")
		public Double highCount;

		void collectGroups(HashSet<String> out) {
			if (groups != null) {
				out.addAll(Arrays.asList(groups));
			}
			for (Selection[] list : new Selection[][] {and, or, not}) {
				if (list != null) {
					for (Selection s : list) {
						s.collectGroups(out);
					}
				}
			}
		}

		double volume(HashMap<String, Distribution> distribution) {
			double volume = 1;
			if (lowWeight != null || highWeight != null || lowCount != null || highCount != null) {
				Distribution collected = new Distribution();
				for (String group : groups) {
					Distribution result = distribution.get(group);
					if (result != null) {
						collected.add(result);
					}
				}
				if (lowWeight != null || highWeight != null) {
					if (lowWeight != null && lowWeight > collected.percentage) {
						return 0;
					}
					if (highWeight != null && !(highWeight < collected.percentage)) {
						double low = lowWeight == null ? 0 : lowWeight;
						volume *= (collected.percentage - low) / (highWeight - low);
					}
				}
				if (lowCount != null || highCount != null) {
					if (lowCount != null && lowCount > collected.count) {
						return 0;
					}
					if (highCount != null && !(highCount < collected.count)) {
						double low = lowCount == null ? 0 : lowCount;
						volume *= (collected.count - low) / (highCount - low);
					}
				}
			}
			if (and != null) {
				for (Selection s : and) {
					volume *= s.volume(distribution);
				}
			}
			if (or != null) {
				double orVolume = 0;
				for (Selection s : or) {
					orVolume = Math.max(orVolume, s.volume(distribution));
				}
				volume *= orVolume;
			}
			if (not != null) {
				for (Selection s : not) {
					if (s.volume(distribution) > 0) {
						return 0;
					}
				}
			}
			return volume;
		}
	}

	/** A named feature ("tree", "rock", "water"...). */
	static final class Feature extends Selection {
		public AmbientStackType stack = AmbientStackType.overwrite;
		transient String name;
	}
}
