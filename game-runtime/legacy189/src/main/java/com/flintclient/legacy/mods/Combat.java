package com.flintclient.legacy.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.HitResult;

/**
 * What the client can know about your fights, for Reach Display and Combo Display. A hit is an attack on the entity under
 * your crosshair; its reach is the distance from your eyes to the point the crosshair met its hitbox. A hit lands when the
 * target flashes hurt within a few ticks of it; landed hits in a row make a combo, which ends when you are hurt or after a
 * while without a landed hit.
 */
public final class Combat {
	private static double lastReach = -1;
	private static long lastReachAt;
	private static int combo;
	private static long lastLandedAt;
	private static Entity pending;
	private static int pendingTicks;
	private static boolean playerWasHurt;

	private Combat() {
	}

	/** You attacked {@code target} (from the interaction manager). */
	public static void attacked(Entity target) {
		Minecraft mc = Minecraft.getInstance();
		HitResult hit = mc.crosshairTarget;
		if (mc.player != null && hit != null && hit.type == HitResult.Type.ENTITY && hit.entity == target && hit.facePos != null) {
			Vec3d eyes = mc.player.getEyePosition(1.0F);
			lastReach = eyes.distanceTo(hit.facePos);
			lastReachAt = System.currentTimeMillis();
		}
		pending = target;
		pendingTicks = 0;
		// The shared combat tracker (Combo Counter reads it, as on every line).
		com.flintclient.client.control.feature.Features.COMBAT.swing(target.getNetworkId(), lastReach,
			com.flintclient.client.control.feature.Features.nowMs());
	}

	/** Every client tick in a world. */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		boolean hurt = mc.player.damagedTimer > 0;
		if (hurt && !playerWasHurt) {
			combo = 0;
			com.flintclient.client.control.feature.Features.COMBAT.hurt(Float.NaN, com.flintclient.client.control.feature.Features.nowMs());
		}
		playerWasHurt = hurt;
		if (pending != null) {
			pendingTicks++;
			if (pending instanceof LivingEntity && ((LivingEntity) pending).damagedTimer >= 9) {
				combo++;
				lastLandedAt = System.currentTimeMillis();
				com.flintclient.client.control.feature.Features.COMBAT.landed(pending.getNetworkId(),
					pending instanceof net.minecraft.entity.living.player.PlayerEntity, com.flintclient.client.control.feature.Features.nowMs());
				pending = null;
			} else if (pendingTicks > 3) {
				pending = null;
			}
		}
	}

	/** The reach of your last hit in blocks, or -1 when there is none yet. */
	public static double lastReach() {
		return lastReach;
	}

	public static long lastReachAt() {
		return lastReachAt;
	}

	/** Landed hits in a row, or 0 once the combo has ended ({@code timeout} ms without a landed hit). */
	public static int combo(long timeout) {
		if (combo > 0 && System.currentTimeMillis() - lastLandedAt > timeout) {
			combo = 0;
		}
		return combo;
	}
}
