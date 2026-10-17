package com.flintclient.client.control.platform;

/**
 * Fields Flint adds to Minecraft 26.3's item entity render state (by mixin): what Item Physics and 2D Items decided for
 * this frame's dropped item. Set when the state is extracted, read when it is submitted.
 */
public interface DroppedItemState {
	/** 0: vanilla. Bit 1: Item Physics. Bit 2: 2D item icon. Bit 4: 2D sprite (faces the camera). */
	int flint$mode();

	void flint$mode(int mode);

	/** Item Physics: the tumble angle about X (degrees) and the yaw (degrees). */
	float flint$angle();

	float flint$yaw();

	void flint$pose(float angle, float yaw);
}
