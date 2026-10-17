package com.flintclient.client.control.feature;

/**
 * The tumble of a dropped item for Item Physics: while it falls it turns end over end at the module's speed; once it
 * lands it settles over {@value #SETTLE_TICKS} ticks into its resting angle (flat items lie down, block-shaped ones stand)
 * by the shortest way round, and stays there. Angles in degrees. Pure Java.
 */
public final class DroppedItemPose {
	/** Ticks a landed item takes to settle. */
	public static final float SETTLE_TICKS = 3;
	/** Degrees a tick at 100% rotation speed. */
	static final float DEGREES_PER_TICK = 20;

	private DroppedItemPose() {
	}

	/** The tumble angle {@code ageTicks} into its fall, at {@code speedPercent} (in water a quarter as fast). */
	public static float tumble(float ageTicks, int speedPercent, float bobOffset, boolean inWater) {
		float speed = DEGREES_PER_TICK * speedPercent / 100F * (inWater ? 0.25F : 1F);
		return wrap(ageTicks * speed + bobOffset * 57.29578F);
	}

	/** The angle {@code groundTicks} after landing at {@code landAngle}, heading for {@code restAngle}. */
	public static float settle(float landAngle, float restAngle, float groundTicks) {
		float t = Math.max(0, Math.min(1, groundTicks / SETTLE_TICKS));
		// Ease out: quick at first, gentle at the end.
		t = 1 - (1 - t) * (1 - t);
		float delta = wrap(restAngle - landAngle);
		return wrap(landAngle + delta * t);
	}

	/** The item's resting yaw: fixed per item (from its random bob offset), so a pile doesn't all face one way. */
	public static float restingYaw(float bobOffset) {
		return wrap(bobOffset * 57.29578F);
	}

	/** How high the centre of a box {@code height x depth} turned {@code angle} degrees about X must be to sit on the ground. */
	public static float lift(float height, float depth, float angle) {
		double r = Math.toRadians(angle);
		return (float) (Math.abs(height * Math.cos(r)) + Math.abs(depth * Math.sin(r))) / 2 + 0.01F;
	}

	/** {@code degrees} in (-180, 180]. */
	static float wrap(float degrees) {
		float d = degrees % 360;
		if (d <= -180) {
			d += 360;
		} else if (d > 180) {
			d -= 360;
		}
		return d;
	}
}
