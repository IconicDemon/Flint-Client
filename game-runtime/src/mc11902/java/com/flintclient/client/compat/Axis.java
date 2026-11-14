package com.flintclient.client.compat;

import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;

/**
 * 1.19.3's rotation axes ({@code com.mojang.math.Axis}) on Minecraft 1.19.2, whose PoseStack turns by its own quaternions:
 * a turn about an axis, ready for {@code PoseStack.mulPose}.
 */
public interface Axis {
	Axis XN = new Of(Vector3f.XN);
	Axis XP = new Of(Vector3f.XP);
	Axis YN = new Of(Vector3f.YN);
	Axis YP = new Of(Vector3f.YP);
	Axis ZN = new Of(Vector3f.ZN);
	Axis ZP = new Of(Vector3f.ZP);

	Quaternion rotation(float radians);

	default Quaternion rotationDegrees(float degrees) {
		return rotation(degrees * (float) (Math.PI / 180.0));
	}

	/** The turn about {@code axis}. */
	record Of(Vector3f axis) implements Axis {
		@Override
		public Quaternion rotation(float radians) {
			return axis.rotation(radians);
		}
	}
}
