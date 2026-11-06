package com.flintclient.client.control.platform;

/** Room on a living thing's render state for Mob Overlay's fill colour ({@code 0xAARRGGBB}; 0: none). */
public interface MobFillState {
	int flint$fill();

	void flint$fill(int argb);
}
