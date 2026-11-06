package com.flintclient.client.compat.state;

import com.flintclient.client.control.platform.DroppedItemState;

/**
 * A dropped item's render state as Flint's features read it (see {@link EntityRenderState}), with the room the other lines
 * add by mixin ({@link DroppedItemState}).
 */
public class ItemEntityRenderState extends EntityRenderState implements DroppedItemState, com.flintclient.client.control.platform.LootBeamState {
	public final ItemStackRenderState item = new ItemStackRenderState();
	public float bobOffset;
	public int count;
	public int seed;

	private int mode;
	private float angle;
	private float yaw;
	private float scale = 1.0F;
	private boolean hidden;
	private com.flintclient.client.control.platform.Mc263LootBeams.@org.jspecify.annotations.Nullable Draw loot;

	@Override
	public com.flintclient.client.control.platform.Mc263LootBeams.@org.jspecify.annotations.Nullable Draw flint$loot() {
		return loot;
	}

	@Override
	public void flint$loot(com.flintclient.client.control.platform.Mc263LootBeams.@org.jspecify.annotations.Nullable Draw draw) {
		loot = draw;
	}

	@Override
	public int flint$mode() {
		return mode;
	}

	@Override
	public void flint$mode(int mode) {
		this.mode = mode;
	}

	@Override
	public float flint$angle() {
		return angle;
	}

	@Override
	public float flint$yaw() {
		return yaw;
	}

	@Override
	public void flint$pose(float angle, float yaw) {
		this.angle = angle;
		this.yaw = yaw;
	}

	@Override
	public float flint$scale() {
		return scale;
	}

	@Override
	public void flint$scale(float scale) {
		this.scale = scale;
	}

	@Override
	public boolean flint$hidden() {
		return hidden;
	}

	@Override
	public void flint$hidden(boolean hidden) {
		this.hidden = hidden;
	}
}
