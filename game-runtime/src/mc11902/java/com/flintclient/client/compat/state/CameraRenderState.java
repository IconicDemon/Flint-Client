package com.flintclient.client.compat.state;

import com.mojang.math.Quaternion;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The camera as Flint's features read it from a render state (1.21.9's {@code CameraRenderState}); on Minecraft 1.19.2 its
 * turn is copied from the camera's own quaternion into JOML's, which Flint's features compute with.
 */
public final class CameraRenderState {
	public Vec3 pos = Vec3.ZERO;
	public final Quaternionf orientation = new Quaternionf();

	/** This one, set from {@code camera}. */
	public CameraRenderState set(Camera camera) {
		pos = camera.getPosition();
		Quaternion turn = camera.rotation();
		orientation.set(turn.i(), turn.j(), turn.k(), turn.r());
		return this;
	}
}
