package com.flintclient.client.control.platform;

import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/** Room on a living thing's render state for the entity it was filled from (Animations works on the player itself). */
public interface EntityRef {
	@Nullable
	LivingEntity flint$entity();

	void flint$entity(@Nullable LivingEntity entity);
}
