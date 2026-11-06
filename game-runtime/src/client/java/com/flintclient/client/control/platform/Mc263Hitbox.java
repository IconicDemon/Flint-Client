/*
 * Parts adapted from Combat Hitboxes (combat-hitbox) by Sootysplash, licensed under the Apache License 2.0:
 * http://www.apache.org/licenses/LICENSE-2.0 (full text in META-INF/licenses/Apache-2.0-CombatHitboxes.txt). Changed for
 * Flint: Flint's settings model, its kinds of entity, reach colors and distance limit.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.Hitbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Hitbox, as combat-hitbox draws Minecraft's F3+B hitboxes: Minecraft still decides when they show (F3+B, or the module's
 * toggle key, which flips the same switch) and for which entities (not invisible ones, not you in first person, only ones on
 * screen); this draws each one instead of Minecraft: the box in the base color (or the target, hurt, in-reach or kind's
 * color), an optional outline under it, the position point, the seat box of a rider, the eye line, the ender dragon's
 * parts and the look direction as a line or Minecraft's arrow, with the line width switching to the second one past the
 * set distance. Stuck arrows and bee stingers can be hidden. Fault-isolated.
 */
public final class Mc263Hitbox {
	private static final HookGuard GUARD = new HookGuard("hitbox",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final int HITBOX = Mods.index(Hitbox.ID);
	/** Minecraft's seat box color (yellow) and ender dragon part color (lime). */
	private static final int SEAT = 0xFFFFFF00;
	private static final int DRAGON_PART = ARGB.colorFromFloat(1.0F, 0.25F, 1.0F, 0.0F);
	/** Where Minecraft hands Flint the hitboxes while extracting entities (1.21.10): drawn once the gizmo collection opens. */
	private static final int QUEUE = 512;
	private static final Entity[] QUEUED = new Entity[QUEUE];
	private static final float[] QUEUED_TICKS = new float[QUEUE];
	private static int queued;
	private static boolean keyWas;
	private static int drawn;

	private Mc263Hitbox() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(HITBOX);
	}

	/**
	 * Minecraft is about to draw {@code entity}'s hitbox (it passed F3+B's own checks): true when Flint drew it instead (or
	 * leaves it out on purpose), so Minecraft draws nothing.
	 */
	public static boolean draw(Entity entity, float partialTicks) {
		if (!on()) {
			return false;
		}
		try {
			drawOne(entity, partialTicks, Mods.state(HITBOX));
			return true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return false;
		}
	}

	/** Minecraft 1.21.10 hands the hitbox over while extracting entities: kept for {@link #frame}. True when Flint takes it. */
	public static boolean queue(Entity entity, float partialTicks) {
		if (!on()) {
			return false;
		}
		if (queued < QUEUE) {
			QUEUED[queued] = entity;
			QUEUED_TICKS[queued] = partialTicks;
			queued++;
		}
		return true;
	}

	/** From the frame's gizmo collection: the hitboxes queued while entities were extracted (1.21.10; none elsewhere). */
	public static void frame() {
		int n = queued;
		queued = 0;
		if (n == 0 || !on()) {
			java.util.Arrays.fill(QUEUED, 0, n, null);
			return;
		}
		try {
			ModuleState state = Mods.state(HITBOX);
			for (int i = 0; i < n; i++) {
				drawOne(QUEUED[i], QUEUED_TICKS[i], state);
				QUEUED[i] = null;
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Hide stuck arrows: no arrows or bee stingers in players while the module is on and asks. */
	public static int stuck(int count) {
		return count > 0 && on() && Mods.state(HITBOX).bool(Hitbox.HIDE_ARROWS) ? 0 : count;
	}

	/** Each client tick: the toggle key flips Minecraft's own hitbox switch (F3+B). */
	static void tick(boolean playing) {
		if (!GUARD.on()) {
			return;
		}
		try {
			boolean down = playing && Mods.on(HITBOX) && Mc263Features.keyHeld(Mods.state(HITBOX).text(Hitbox.TOGGLE_KEY));
			if (down && !keyWas) {
				Minecraft.getInstance().debugEntries.toggleStatus(DebugScreenEntries.ENTITY_HITBOXES);
			}
			keyWas = down;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	/** Hitboxes Flint drew since the last call (for the dev tour). */
	public static int drawnAndReset() {
		int n = drawn;
		drawn = 0;
		return n;
	}

	private static int argb(ModuleState s, com.flintclient.client.control.model.RgbSetting color,
		com.flintclient.client.control.model.IntSetting opacity) {
		return Math.round(s.integer(opacity) * 2.55F) << 24 | s.rgb(color);
	}

	private static void drawOne(Entity entity, float partialTicks, ModuleState s) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		if (s.bool(Hitbox.SKIP_FIREWORKS) && entity instanceof FireworkRocketEntity) {
			return;
		}
		Hitbox.Group group = Mc263World.groupOf(entity);
		if (!s.bool(group.enabled())) {
			return;
		}
		double distance = player.distanceTo(entity);
		if (s.bool(Hitbox.LIMIT) && distance > s.integer(Hitbox.DISTANCE)) {
			return;
		}
		Vec3 latest = entity.position();
		Vec3 current = entity.getPosition(partialTicks);
		Vec3 offset = current.subtract(latest);
		AABB box = entity.getBoundingBox().move(offset);
		boolean inReach = entity != player && entity instanceof LivingEntity
			&& box.distanceToSqr(player.getEyePosition(partialTicks)) <= player.entityInteractionRange() * player.entityInteractionRange();
		if (s.bool(Hitbox.ONLY_HITTABLE) && !inReach) {
			return;
		}
		float width = distance > s.decimal(Hitbox.DISTANCE_2) ? s.decimal(Hitbox.WIDTH_2) : s.decimal(Hitbox.WIDTH);
		int color;
		if (s.bool(Hitbox.DAMAGED) && entity instanceof LivingEntity living && living.hurtTime != 0) {
			color = argb(s, Hitbox.DAMAGED_COLOR, Hitbox.DAMAGED_OPACITY);
		} else if (s.bool(Hitbox.TARGET) && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() == entity) {
			color = argb(s, Hitbox.TARGET_COLOR, Hitbox.TARGET_OPACITY);
		} else if (s.bool(Hitbox.HITTABLE) && inReach) {
			color = Math.round(s.integer(Hitbox.MAIN_OPACITY) * 2.55F) << 24 | s.rgb(Hitbox.HITTABLE_COLOR);
		} else {
			int own = s.rgb(group.color());
			color = Math.round(s.integer(Hitbox.MAIN_OPACITY) * 2.55F) << 24 | (own == 0xFFFFFF ? s.rgb(Hitbox.MAIN_COLOR) : own);
		}
		if (s.bool(Hitbox.OUTLINE)) {
			Gizmos.cuboid(box, GizmoStyle.stroke(argb(s, Hitbox.OUTLINE_COLOR, Hitbox.OUTLINE_OPACITY), width * s.decimal(Hitbox.OUTLINE_SIZE)));
		}
		Gizmos.cuboid(box, GizmoStyle.stroke(color, width));
		Gizmos.point(current, color, 2.0F);
		Entity vehicle = entity.getVehicle();
		if (vehicle != null) {
			float half = Math.min(vehicle.getBbWidth(), entity.getBbWidth()) / 2.0F;
			Vec3 seat = vehicle.getPassengerRidingPosition(entity).add(offset);
			Gizmos.cuboid(new AABB(seat.x - half, seat.y, seat.z - half, seat.x + half, seat.y + 0.0625, seat.z + half), GizmoStyle.stroke(SEAT, width));
		}
		if (entity instanceof LivingEntity && s.bool(Hitbox.EYE_LEVEL)) {
			double eye = box.minY + entity.getEyeHeight();
			Gizmos.cuboid(new AABB(box.minX, eye - 0.01F, box.minZ, box.maxX, eye + 0.01F, box.maxZ),
				GizmoStyle.stroke(argb(s, Hitbox.EYE_COLOR, Hitbox.EYE_OPACITY), width));
		}
		if (entity instanceof EnderDragon dragon) {
			for (Entity part : dragon.getSubEntities()) {
				Vec3 partOffset = part.getPosition(partialTicks).subtract(part.position());
				Gizmos.cuboid(part.getBoundingBox().move(partOffset), GizmoStyle.stroke(DRAGON_PART));
			}
		}
		if (s.bool(Hitbox.LOOK)) {
			Vec3 eyes = current.add(0.0, entity.getEyeHeight(), 0.0);
			Vec3 end = eyes.add(entity.getViewVector(partialTicks).scale(2.0));
			int look = argb(s, Hitbox.LOOK_COLOR, Hitbox.LOOK_OPACITY);
			if (s.bool(Hitbox.LOOK_LINE)) {
				Gizmos.line(eyes, end, look, width);
			} else {
				Gizmos.arrow(eyes, end, look, width);
			}
		}
		drawn++;
	}
}
