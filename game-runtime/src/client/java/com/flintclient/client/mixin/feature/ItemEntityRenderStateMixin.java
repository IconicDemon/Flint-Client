package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.DroppedItemState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Room on the dropped item's render state for what Item Physics and 2D Items decided ({@link DroppedItemState}) and what Loot Beams
 * draws over it.
 */
@Mixin(ItemEntityRenderState.class)
abstract class ItemEntityRenderStateMixin implements DroppedItemState, com.flintclient.client.control.platform.LootBeamState {
	@Unique
	private com.flintclient.client.control.platform.Mc263LootBeams.@org.jspecify.annotations.Nullable Draw flint$loot;

	@Override
	public com.flintclient.client.control.platform.Mc263LootBeams.@org.jspecify.annotations.Nullable Draw flint$loot() {
		return flint$loot;
	}

	@Override
	public void flint$loot(com.flintclient.client.control.platform.Mc263LootBeams.@org.jspecify.annotations.Nullable Draw draw) {
		flint$loot = draw;
	}

	@Unique
	private int flint$mode;
	@Unique
	private float flint$angle;
	@Unique
	private float flint$yaw;
	@Unique
	private float flint$scale = 1;
	@Unique
	private boolean flint$hidden;

	@Override
	public float flint$scale() {
		return flint$scale;
	}

	@Override
	public void flint$scale(float scale) {
		flint$scale = scale;
	}

	@Override
	public boolean flint$hidden() {
		return flint$hidden;
	}

	@Override
	public void flint$hidden(boolean hidden) {
		flint$hidden = hidden;
	}

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
