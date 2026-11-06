/*
 * Dropped Item Tweaks' rendered count, scales and static items are adapted from Dropped Item Tweaks (LGPL-3.0; licence text
 * in META-INF/licenses/LGPL-3.0-DroppedItemTweaks.txt). Realistic's pose is Realistic Item Drops' (Silabear, Stoupy51;
 * CC-BY-SA-4.0, META-INF/licenses/CC-BY-SA-4.0-RealisticItemDrops.txt): the angles, sizes and rest heights its armor-stand
 * heads give a dropped item.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.DroppedItemPose;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.client.control.model.mods.VisualMods.ItemPhysics;
import com.flintclient.client.control.model.mods.VisualMods.Items2d;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Minecraft's side of Item Physics and 2D Items: called from {@code ItemEntityRenderer} while it extracts and submits a dropped
 * item. Only the pose, the size, the number of copies and the model the item is drawn with change; the entity itself is never
 * touched (beyond Flint's own remembered numbers). Render thread; fault-isolated (a failure leaves dropped items vanilla).
 */
public final class Mc263Items {
	static final int PHYSICS = 1;
	static final int ICON = 2;
	static final int SPRITE = 4;
	/** Realistic Item Drops' pose (with {@link #PHYSICS}). */
	static final int REALISTIC = 8;
	/** Dropped Item Tweaks' static items alone: Minecraft's pose without the bob. */
	static final int STATIC = 16;
	/** A block-shaped model lying on its side (Realistic). */
	static final int BLOCK = 32;
	/** 2D icons are drawn this many times smaller than their inventory model (a block's icon is a full block). */
	private static final float ICON_SCALE = 0.5F;
	/** FlatDrop's sprites have no thickness: the front quads only (the back ones are culled, facing away). */
	private static final float ICON_DEPTH = 0.001F;
	private static final float FLAT_DEPTH = 0.0625F;
	/** Realistic Item Drops' sizes against Minecraft's dropped ones: 0.625 blocks for a flat item and a block. */
	private static final float REALISTIC_FLAT = 1.25F;
	private static final float REALISTIC_BLOCK = 2.5F;
	/** Where its armor stands rest them: a flat item's plane 0.062 above the ground, a block 0.026 above it. */
	private static final float REST_FLAT = 0.062F;
	private static final float REST_BLOCK_GAP = 0.026F;
	private static final int PHYSICS_MODULE = Mods.index(VisualMods.ItemPhysics.ID);
	private static final int ICONS_MODULE = Mods.index(VisualMods.Items2d.ID);
	private static final HookGuard GUARD = new HookGuard("dropped item rendering",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** Item ids for Dropped Item Tweaks' custom scale list, looked up once per item. */
	private static final Map<Item, String> IDS = new IdentityHashMap<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private Mc263Items() {
	}

	/** After vanilla extracted a dropped item: picks the model, the copies and the size, and works out Item Physics' angles. */
	public static void extract(ItemEntity entity, ItemEntityRenderState state, ItemModelResolver resolver) {
		DroppedItemState extra = (DroppedItemState) state;
		extra.flint$mode(0);
		extra.flint$scale(1);
		if (!GUARD.on()) {
			return;
		}
		try {
			int mode = 0;
			boolean physicsOn = Mods.on(PHYSICS_MODULE);
			ModuleState physics = physicsOn ? Mods.state(PHYSICS_MODULE) : null;
			int style = physicsOn ? physics.option(ItemPhysics.STYLE) : 2;
			ItemStack stack = entity.getItem();
			AABB ground = state.item.getModelBoundingBox();
			boolean flatModel = ground.getZsize() <= FLAT_DEPTH;
			if (style != 2) {
				mode |= PHYSICS;
				if (style == 0) {
					mode |= REALISTIC;
					if (!flatModel && stack.getItem() instanceof BlockItem && physics.bool(ItemPhysics.BLOCKS_ON_SIDE)) {
						mode |= BLOCK;
					}
				}
			}
			boolean icons = Mods.on(ICONS_MODULE) && !state.item.isEmpty();
			ModuleState icon = icons ? Mods.state(ICONS_MODULE) : null;
			if (icons && (flatModel || !icon.bool(Items2d.BLOCKS_3D))) {
				mode |= ICON;
				mode &= ~BLOCK;
				if ((mode & PHYSICS) == 0 && icon.option(Items2d.RENDER_AS) == 0) {
					mode |= SPRITE;
				}
				resolver.updateForNonLiving(state.item, stack, ItemDisplayContext.GUI, entity);
			}
			if (physicsOn) {
				// Dropped Item Tweaks: the copies a stack shows and its size.
				int most = physics.integer(ItemPhysics.STACK_COUNT);
				if (most != 0) {
					state.count = Math.min(most, stack.getCount());
				} else if ((mode & REALISTIC) != 0 && physics.bool(ItemPhysics.ONE_MODEL)) {
					state.count = 1;
				}
				float scale = physics.decimal(ItemPhysics.ITEM_SCALE);
				float custom = physics.decimal(ItemPhysics.CUSTOM_SCALE);
				if (custom != 0 && listed(physics.ids(ItemPhysics.CUSTOM_ITEMS), stack.getItem())) {
					scale = custom;
				}
				if ((mode & REALISTIC) != 0 && physics.bool(ItemPhysics.REALISTIC_SIZE)) {
					scale *= (mode & BLOCK) != 0 ? REALISTIC_BLOCK : REALISTIC_FLAT;
				}
				extra.flint$scale(scale);
				if (style == 2 && physics.bool(ItemPhysics.STATIC) && (mode & ICON) == 0) {
					mode |= STATIC;
				}
			}
			if ((mode & ICON) != 0 && icon.bool(Items2d.ONE_SPRITE)) {
				state.count = 1;
			}
			if ((mode & PHYSICS) != 0) {
				pose(entity, state, extra, physics, mode);
			}
			extra.flint$mode(mode);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			extra.flint$mode(0);
		}
	}

	/** Item Physics' angles: Realistic's fixed pose (and its landing burst), or the tumble and its settling. */
	private static void pose(ItemEntity entity, ItemEntityRenderState state, DroppedItemState extra, ModuleState physics, int mode) {
		DroppedItemMemory memory = (DroppedItemMemory) entity;
		boolean water = entity.isInWater();
		float age = state.ageInTicks;
		boolean landed = entity.onGround() && !water;
		if ((mode & REALISTIC) != 0) {
			if (landed && memory.flint$groundSince() < 0) {
				memory.flint$landed(age, 90);
				if ((mode & BLOCK) != 0 && physics.bool(ItemPhysics.LANDING_PARTICLES)) {
					burst(entity);
				}
			} else if (!landed && memory.flint$groundSince() >= 0) {
				memory.flint$landed(-1, 0);
			}
			extra.flint$pose(90, physics.bool(ItemPhysics.SAME_HEADING) ? 0 : DroppedItemPose.restingYaw(state.bobOffset));
			return;
		}
		int speed = physics.integer(ItemPhysics.ROTATION_SPEED);
		boolean flat = (mode & ICON) != 0 || state.item.getModelBoundingBox().getZsize() <= FLAT_DEPTH;
		float angle;
		if (landed) {
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

	/** Realistic Item Drops' landing: 50 of the block's particles, each thrown at a random speed (as /particle ... 0 0 0 1 50 force). */
	private static void burst(ItemEntity entity) {
		if (!(entity.getItem().getItem() instanceof BlockItem block)) {
			return;
		}
		BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, block.getBlock().defaultBlockState());
		Minecraft mc = Minecraft.getInstance();
		for (int i = 0; i < 50; i++) {
			mc.particleEngine.createParticle(particle, entity.getX(), entity.getY() + 0.2, entity.getZ(), RANDOM.nextGaussian(), RANDOM.nextGaussian(),
				RANDOM.nextGaussian());
		}
	}

	private static boolean listed(List<String> ids, Item item) {
		return Collections.binarySearch(ids, id(item)) >= 0;
	}

	/** {@code item}'s id ({@code minecraft:golden_apple}), looked up once per item and kept. Render thread. */
	static String id(Item item) {
		return IDS.computeIfAbsent(item, i -> BuiltInRegistries.ITEM.getKey(i).toString());
	}

	/** Replaces vanilla's lift (bob and model offset) for a dropped item Flint draws; false: vanilla's. */
	public static boolean lift(PoseStack pose, ItemEntityRenderState state) {
		DroppedItemState extra = (DroppedItemState) state;
		int mode = extra.flint$mode();
		if (mode == 0) {
			return false;
		}
		AABB box = state.item.getModelBoundingBox();
		if (mode == STATIC) {
			// Dropped Item Tweaks: Minecraft's lift with the bob at its lowest point.
			pose.translate(0, (float) -box.minY + 0.0625F, 0);
			return true;
		}
		boolean icon = (mode & ICON) != 0;
		float k = (icon ? ICON_SCALE : 1) * extra.flint$scale();
		float height = (float) box.getYsize() * k;
		float depth = icon ? 0 : (float) box.getZsize() * k;
		if ((mode & REALISTIC) != 0) {
			// Lying flat: a flat item's plane, or a block's side, just above the ground.
			pose.translate(0, (mode & BLOCK) != 0 ? depth / 2 + REST_BLOCK_GAP : Math.max(REST_FLAT, depth / 2 + 0.01F), 0);
		} else if ((mode & PHYSICS) != 0) {
			pose.translate(0, DroppedItemPose.lift(height, depth, extra.flint$angle()), 0);
		} else {
			// FlatDrop: a half-block sprite whose bottom bobs as Minecraft's items do.
			float bob = Mth.sin(state.ageInTicks / 10.0F + state.bobOffset) * 0.1F + 0.1F;
			pose.translate(0, bob + height / 2, 0);
		}
		return true;
	}

	/** Replaces vanilla's spin for a dropped item Flint draws; false: vanilla's. Leaves the model centred on the pivot. */
	public static boolean turn(PoseStack pose, ItemEntityRenderState state, float spin, CameraRenderState camera) {
		DroppedItemState extra = (DroppedItemState) state;
		int mode = extra.flint$mode();
		if (mode == 0 || mode == STATIC) {
			return false;
		}
		if ((mode & PHYSICS) != 0) {
			com.flintclient.client.compat.McCompat.rotate(pose, Axis.YP, extra.flint$yaw() * Mth.DEG_TO_RAD);
			com.flintclient.client.compat.McCompat.rotate(pose, Axis.XP, extra.flint$angle() * Mth.DEG_TO_RAD);
			if ((mode & BLOCK) != 0) {
				// On its side: top to the south, the south face up.
				com.flintclient.client.compat.McCompat.rotate(pose, Axis.YP, Mth.PI);
			}
		} else if ((mode & SPRITE) != 0) {
			// FlatDrop: upright, turned to face the camera's heading.
			com.flintclient.client.compat.McCompat.rotate(pose, Axis.YP, (180 - com.flintclient.client.compat.McCompat.mainCamera().yaw()) * Mth.DEG_TO_RAD);
		} else {
			com.flintclient.client.compat.McCompat.rotate(pose, Axis.YP, spin);
		}
		float scale = extra.flint$scale();
		if ((mode & ICON) != 0) {
			pose.scale(ICON_SCALE * scale, ICON_SCALE * scale, ICON_DEPTH);
		} else if (scale != 1) {
			pose.scale(scale, scale, scale);
		}
		AABB box = state.item.getModelBoundingBox();
		pose.translate((float) -box.getCenter().x, (float) -box.getCenter().y, (float) -box.getCenter().z);
		return true;
	}

	/** Whether the size ({@link DroppedItemState#flint$scale}) was already applied by {@link #turn} (Flint's own pose). */
	public static boolean scaledByFlint(ItemEntityRenderState state) {
		int mode = ((DroppedItemState) state).flint$mode();
		return mode != 0 && mode != STATIC;
	}
}
