package com.flintclient.client.compat.state;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** The camera as Flint's features read it from a render state (1.21.9's {@code CameraRenderState}). */
public final class CameraRenderState {
	public Vec3 pos = Vec3.ZERO;
	public final Quaternionf orientation = new Quaternionf();

	/** This one, set from {@code camera}. */
	public CameraRenderState set(Camera camera) {
		pos = camera.getPosition();
		orientation.set(camera.rotation());
		return this;
	}
}
