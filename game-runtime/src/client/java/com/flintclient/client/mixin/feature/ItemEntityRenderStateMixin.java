package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.DroppedItemState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Room on the dropped item's render state for what Item Physics and 2D Items decided ({@link DroppedItemState}). */
@Mixin(ItemEntityRenderState.class)
abstract class ItemEntityRenderStateMixin implements DroppedItemState {
	@Unique
	private int flint$mode;
	@Unique
	private float flint$angle;
	@Unique
	private float flint$yaw;

	@Override
	public int flint$mode() {
		return flint$mode;
	}

	@Override
	public void flint$mode(int mode) {
		flint$mode = mode;
	}

	@Override
	public float flint$angle() {
		return flint$angle;
	}

	@Override
	public float flint$yaw() {
		return flint$yaw;
	}

	@Override
	public void flint$pose(float angle, float yaw) {
		flint$angle = angle;
		flint$yaw = yaw;
	}
}
