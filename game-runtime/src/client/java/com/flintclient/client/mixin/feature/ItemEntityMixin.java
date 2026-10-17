package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.DroppedItemMemory;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Item Physics' memory on a dropped item: when it landed and at which angle (client side only, never saved). */
@Mixin(ItemEntity.class)
abstract class ItemEntityMixin implements DroppedItemMemory {
	@Unique
	private float flint$groundSince = -1;
	@Unique
	private float flint$landAngle;

	@Override
	public float flint$groundSince() {
		return flint$groundSince;
	}

	@Override
	public float flint$landAngle() {
		return flint$landAngle;
	}

	@Override
	public void flint$landed(float since, float angle) {
		flint$groundSince = since;
		flint$landAngle = angle;
	}
}
