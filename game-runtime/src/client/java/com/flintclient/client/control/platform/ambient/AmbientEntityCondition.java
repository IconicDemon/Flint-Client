package com.flintclient.client.control.platform.ambient;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * A rule about the entities around you (the warden region: a warden within 40 blocks): by type, name, tag, team or NBT,
 * with fades over distance, rotation and how many there are. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
final class AmbientEntityCondition {
	public AmbientCondition.MinMaxFade distance;
	@SerializedName("distance-x")
	public AmbientCondition.MinMaxFade distanceX;
	@SerializedName("distance-y")
	public AmbientCondition.MinMaxFade distanceY;
	@SerializedName("distance-z")
	public AmbientCondition.MinMaxFade distanceZ;
	public AmbientCondition.MinMaxFade count;
	@JsonAdapter(StringJson.class)
	public String[] name;
	@SerializedName("bad-name")
	@JsonAdapter(StringJson.class)
	public String[] badName;
	@JsonAdapter(StringJson.class)
	public String[] type;
	@SerializedName("bad-type")
	@JsonAdapter(StringJson.class)
	public String[] badType;
	private transient List<EntityType<?>> parsedType;
	private transient List<EntityType<?>> parsedBadType;
	public Map<String, String> scores;
	@JsonAdapter(StringJson.class)
	public String[] tag;
	@SerializedName("bad-tag")
	@JsonAdapter(StringJson.class)
	public String[] badTag;
	@JsonAdapter(StringJson.class)
	public String[] team;
	@SerializedName("bad-team")
	@JsonAdapter(StringJson.class)
	public String[] badTeam;
	@JsonAdapter(StringJson.class)
	public String[] nbt;
	@SerializedName("bad-nbt")
	@JsonAdapter(StringJson.class)
	public String[] badNbt;
	private transient List<CompoundTag> parsedNbt;
	private transient List<CompoundTag> parsedBadNbt;
	public AmbientCondition.MinMaxFade level;
	public AmbientCondition.MinMaxFade x_rotation;
	public AmbientCondition.MinMaxFade y_rotation;

	void init() {
		parsedType = types(type);
		parsedBadType = types(badType);
		parsedNbt = tags(nbt);
		parsedBadNbt = tags(badNbt);
	}

	private static List<EntityType<?>> types(String[] names) {
		if (names == null) {
			return null;
		}
		List<EntityType<?>> out = new ArrayList<>();
		for (String name : names) {
			Identifier id = Identifier.tryParse(name);
			if (id != null) {
				BuiltInRegistries.ENTITY_TYPE.getOptional(id).ifPresent(out::add);
			}
		}
		return out;
	}

	private static List<CompoundTag> tags(String[] texts) {
		if (texts == null) {
			return null;
		}
		List<CompoundTag> out = new ArrayList<>();
		for (String text : texts) {
			try {
				out.add(TagParser.parseCompoundFully(text));
			} catch (Exception e) {
				AmbientSounds.LOGGER.error("Ambient Sounds: bad entity NBT condition {}", text, e);
			}
		}
		return out;
	}

	double value(AmbientEnvironment env) {
		double highest = 0;
		int counted = 0;
		for (Entity entity : env.entity.all()) {
			if ((name == null || checkName(name, entity)) && (badName == null || !checkName(badName, entity))
				&& (parsedType == null || parsedType.contains(entity.getType())) && (parsedBadType == null || !parsedBadType.contains(entity.getType()))
				&& (tag == null || containsTag(tag, entity)) && (badTag == null || !containsTag(badTag, entity)) && (team == null || isTeam(team, entity))
				&& (badTeam == null || !isTeam(badTeam, entity)) && (parsedNbt == null || hasNbt(parsedNbt, entity))
				&& (parsedBadNbt == null || !hasNbt(parsedBadNbt, entity))) {
				double current = 1;
				if (distance != null) {
					current *= distance.volume(Math.sqrt(env.entity.squaredDistance(entity)));
				}
				if (distanceX != null) {
					current *= distanceX.volume(entity.getX() - env.entity.x());
				}
				if (distanceY != null) {
					current *= distanceY.volume(entity.getY() - env.entity.y());
				}
				if (distanceZ != null) {
					current *= distanceZ.volume(entity.getZ() - env.entity.z());
				}
				if (x_rotation != null) {
					current *= x_rotation.volume(entity.getXRot());
				}
				if (y_rotation != null) {
					current *= y_rotation.volume(entity.getYRot());
				}
				counted++;
				if (current == 1 && count == null) {
					return 1;
				}
				highest = Math.max(current, highest);
			}
		}
		if (count != null) {
			highest *= count.volume(counted);
		}
		return highest;
	}

	private static boolean hasNbt(List<CompoundTag> nbt, Entity entity) {
		CompoundTag tag = AmbientCompat.saveEntity(entity);
		for (CompoundTag check : nbt) {
			if (NbtUtils.compareNbt(check, tag, true)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isTeam(String[] team, Entity entity) {
		return entity instanceof LivingEntity && Arrays.asList(team).contains(entity.getTeam() == null ? "" : entity.getTeam().getName());
	}

	private static boolean containsTag(String[] tag, Entity entity) {
		Set<String> tags = AmbientCompat.entityTags(entity);
		for (String t : tag) {
			if (tags.contains(t)) {
				return true;
			}
		}
		return false;
	}

	private static boolean checkName(String[] name, Entity entity) {
		List<String> names = Arrays.asList(name);
		if (entity instanceof Player p && names.contains(AmbientCompat.playerName(p))) {
			return true;
		}
		return names.contains(entity.getStringUUID()) || entity.hasCustomName() && names.contains(entity.getCustomName().getString());
	}

	/** One string or a list of them. */
	static final class StringJson extends TypeAdapter<String[]> {
		@Override
		public void write(JsonWriter out, String[] value) throws IOException {
			out.beginArray();
			for (String string : value) {
				out.value(string);
			}
			out.endArray();
		}

		@Override
		public String[] read(JsonReader in) throws IOException {
			if (in.peek() == JsonToken.STRING) {
				String value = in.nextString();
				return value.isEmpty() ? new String[0] : new String[] {value};
			}
			List<String> list = new ArrayList<>();
			in.beginArray();
			while (in.hasNext()) {
				list.add(in.nextString());
			}
			in.endArray();
			return list.toArray(new String[0]);
		}
	}
}
