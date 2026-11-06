package com.flintclient.client.control.platform.ambient;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueOutput;

/** The few calls Ambient Sounds makes that differ between Minecraft versions (the 1.21.x rules rewrite these bodies). */
final class AmbientCompat {
	private AmbientCompat() {
	}

	static Identifier id(ResourceKey<?> key) {
		return key.identifier();
	}

	static Identifier id(TagKey<?> tag) {
		return tag.location();
	}

	/** The sun's angle in degrees, 0 at noon (Minecraft's sun angle attribute). */
	static double sunAngle(Level level, Player player) {
		return level.environmentAttributes().getValue(EnvironmentAttributes.SUN_ANGLE, player.position());
	}

	static Set<String> entityTags(Entity entity) {
		return entity.entityTags();
	}

	static String playerName(Player player) {
		return player.getGameProfile().name();
	}

	static CompoundTag saveEntity(Entity entity) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
		entity.saveWithoutId(output);
		return output.buildResult();
	}
}
