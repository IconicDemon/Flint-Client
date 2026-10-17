package com.flintclient.client.control.feature;

/**
 * Freelook: while on, the camera has its own yaw and pitch that the mouse turns; the player keeps facing (and walking)
 * where they did. It starts from the player's own angles, so nothing jumps. Pure Java.
 */
public final class FreelookState {
	private boolean active;
	private float yaw;
	private float pitch;

	public void start(float playerYaw, float playerPitch) {
		active = true;
		yaw = playerYaw;
		pitch = playerPitch;
	}

	public void stop() {
		active = false;
	}

	public boolean active() {
		return active;
	}

	/** Mouse movement, in the same units Minecraft turns the player by (0.15° per unit). */
	public void turn(double dx, double dy, boolean invertYaw, boolean invertPitch) {
		yaw += (float) ((invertYaw ? -dx : dx) * 0.15);
		pitch = Math.clamp(pitch + (float) ((invertPitch ? -dy : dy) * 0.15), -90, 90);
	}

	public float yaw() {
		return yaw;
	}

	public float pitch() {
		return pitch;
	}
}
