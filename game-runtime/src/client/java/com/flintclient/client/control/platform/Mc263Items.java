package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.DroppedItemPose;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.mods.VisualMods;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;

/**
 * Minecraft 26.3's side of Item Physics and 2D Items: called from {@code ItemEntityRenderer} while it extracts and submits
 * a dropped item. Only the pose and the model the item is drawn with change; the entity itself is never touched (beyond
 * Flint's own two remembered numbers). Render thread; fault-isolated (a failure leaves dropped items vanilla).
 */
public final class Mc263Items {
	static final int PHYSICS = 1;
	static final int ICON = 2;
	static final int SPRITE = 4;
	/** 2D icons are drawn this many times smaller than their inventory model (a block's icon is a full block). */
	private static final float ICON_SCALE = 0.5F;
	private static final float ICON_DEPTH = 0.02F;
	private static final float FLAT_DEPTH = 0.0625F;
	private static final int PHYSICS_MODULE = Mods.index(VisualMods.ItemPhysics.ID);
	private static final int ICONS_MODULE = Mods.index(VisualMods.Items2d.ID);
	private static final HookGuard GUARD = new HookGuard("dropped item rendering",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));

	private Mc263Items() {
	}

	/** After vanilla extracted a dropped item: picks the model and works out Item Physics' angles. */
	public static void extract(ItemEntity entity, ItemEntityRenderState state, ItemModelResolver resolver) {
		DroppedItemState extra = (DroppedItemState) state;
		extra.flint$mode(0);
		if (!GUARD.on()) {
			return;
		}
		try {
			int mode = 0;
			boolean physics = Mods.on(PHYSICS_MODULE);
			if (physics) {
				mode |= PHYSICS;
			}
			if (Mods.on(ICONS_MODULE) && !state.item.isEmpty()) {
				mode |= ICON;
				if (!physics && Mods.state(ICONS_MODULE).option(VisualMods.Items2d.RENDER_AS) == 0) {
					mode |= SPRITE;
				}
				resolver.updateForNonLiving(state.item, entity.getItem(), ItemDisplayContext.GUI, entity);
			}
			if (physics) {
				DroppedItemMemory memory = (DroppedItemMemory) entity;
				boolean water = entity.isInWater();
				float age = state.ageInTicks;
				int speed = Mods.state(PHYSICS_MODULE).integer(VisualMods.ItemPhysics.ROTATION_SPEED);
				boolean flat = (mode & ICON) != 0 || state.item.getModelBoundingBox().getZsize() <= FLAT_DEPTH;
				float angle;
				if (entity.onGround() && !water) {
					if (memory.flint$groundSince() < 0) {
						memory.flint$landed(age, DroppedItemPose.tumble(age, speed, state.bobOffset, false));
					}
					angle = DroppedItemPose.settle(memory.flint$landAngle(), flat ? 90 : 0, age - memory.flint$groundSince());
				} else {
					if (memory.flint$groundSince() >= 0) {
						memory.flint$landed(-1, 0);
					}
					angle = DroppedItemPose.tumble(age, speed, state.bobOffset, water);
				}
				extra.flint$pose(angle, DroppedItemPose.restingYaw(state.bobOffset));
			}
			extra.flint$mode(mode);
		} catch (RuntimeException e) {
			GUARD.fail(e);
			extra.flint$mode(0);
		}
	}

	/** Replaces vanilla's lift (bob and model offset) for a dropped item Flint draws; false: vanilla's. */
	public static boolean lift(PoseStack pose, ItemEntityRenderState state) {
		int mode = ((DroppedItemState) state).flint$mode();
		if (mode == 0) {
			return false;
		}
		AABB box = state.item.getModelBoundingBox();
		boolean icon = (mode & ICON) != 0;
		float k = icon ? ICON_SCALE : 1;
		float height = (float) box.getYsize() * k;
		if ((mode & PHYSICS) != 0) {
			float depth = icon ? 0 : (float) box.getZsize();
			pose.translate(0, DroppedItemPose.lift(height, depth, ((DroppedItemState) state).flint$angle()), 0);
		} else {
			float bob = Mth.sin(state.ageInTicks / 10.0F + state.bobOffset) * 0.1F + 0.1F;
			pose.translate(0, bob + 0.0625F + height / 2, 0);
		}
		return true;
	}

	/** Replaces vanilla's spin for a dropped item Flint draws; false: vanilla's. Leaves the model centred on the pivot. */
	public static boolean turn(PoseStack pose, ItemEntityRenderState state, float spin, CameraRenderState camera) {
		int mode = ((DroppedItemState) state).flint$mode();
		if (mode == 0) {
			return false;
		}
		DroppedItemState extra = (DroppedItemState) state;
		if ((mode & PHYSICS) != 0) {
			pose.rotate(Axis.YP, extra.flint$yaw() * Mth.DEG_TO_RAD);
			pose.rotate(Axis.XP, extra.flint$angle() * Mth.DEG_TO_RAD);
		} else if ((mode & SPRITE) != 0) {
			pose.rotate(camera.orientation);
			pose.rotate(Axis.YP, Mth.PI);
		} else {
			pose.rotate(Axis.YP, spin);
		}
		if ((mode & ICON) != 0) {
			pose.scale(ICON_SCALE, ICON_SCALE, ICON_DEPTH);
		}
		AABB box = state.item.getModelBoundingBox();
		pose.translate((float) -box.getCenter().x, (float) -box.getCenter().y, (float) -box.getCenter().z);
		return true;
	}
}
