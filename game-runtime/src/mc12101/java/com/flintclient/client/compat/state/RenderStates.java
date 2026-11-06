package com.flintclient.client.compat.state;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The render state each entity has on Minecraft 1.21.1, kept while the entity is (weakly, so a removed entity's goes with
 * it): Flint's renderer hooks fill it from the entity as it is drawn (the way 1.21.2's extractRenderState does) and hand it
 * to the same feature code the later versions run. Render thread.
 */
public final class RenderStates {
	private static final Map<Entity, EntityRenderState> STATES = new WeakHashMap<>();
	private static final CameraRenderState CAMERA = new CameraRenderState();

	private RenderStates() {
	}

	/**
	 * The entity's state, made on first use. Every living thing gets a humanoid's (1.21.1 can't tell from the entity which
	 * renderer draws it; the extra crouch flag costs nothing), so armor layers find their wearer's state either way.
	 */
	public static EntityRenderState of(Entity entity) {
		EntityRenderState state = STATES.get(entity);
		if (state == null) {
			state = entity instanceof AbstractClientPlayer ? new AvatarRenderState()
				: entity instanceof LivingEntity ? new HumanoidRenderState() : entity instanceof ItemEntity ? new ItemEntityRenderState()
				: new EntityRenderState();
			STATES.put(entity, state);
		}
		return state;
	}

	/** The entity's state, if it has been drawn. */
	public static @Nullable EntityRenderState peek(Entity entity) {
		return STATES.get(entity);
	}

	/** The camera this frame, as the entity renderers see it. */
	public static CameraRenderState camera() {
		EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
		CAMERA.pos = dispatcher.camera != null ? dispatcher.camera.getPosition() : CAMERA.pos;
		CAMERA.orientation.set(dispatcher.cameraOrientation());
		return CAMERA;
	}

	/**
	 * The entity's state filled as 1.21.2's extractRenderState fills it (what Flint reads of it), for this frame; {@code name}
	 * is the nametag it shows (null for none).
	 */
	public static EntityRenderState fill(Entity entity, float partialTick, int light, @Nullable Component name) {
		EntityRenderState state = of(entity);
		Minecraft mc = Minecraft.getInstance();
		state.entityType = entity.getType();
		state.x = Mth.lerp(partialTick, entity.xOld, entity.getX());
		state.y = Mth.lerp(partialTick, entity.yOld, entity.getY());
		state.z = Mth.lerp(partialTick, entity.zOld, entity.getZ());
		state.ageInTicks = entity.tickCount + partialTick;
		state.boundingBoxWidth = entity.getBbWidth();
		state.boundingBoxHeight = entity.getBbHeight();
		state.distanceToCameraSq = mc.getEntityRenderDispatcher().distanceToSqr(entity);
		state.isInvisible = entity.isInvisible();
		state.isDiscrete = entity.isDiscrete();
		state.lightCoords = light;
		state.nameTag = name;
		state.nameTagAttachment = name == null ? null : entity.getAttachments().getNullable(net.minecraft.world.entity.EntityAttachment.NAME_TAG, 0,
			entity.getViewYRot(partialTick));
		if (state instanceof LivingEntityRenderState living && entity instanceof LivingEntity alive) {
			living.bodyRot = Mth.rotLerp(partialTick, alive.yBodyRotO, alive.yBodyRot);
			living.yRot = Mth.rotLerp(partialTick, alive.yHeadRotO, alive.yHeadRot) - living.bodyRot;
			living.xRot = alive.getViewXRot(partialTick);
			living.hasRedOverlay = alive.hurtTime > 0 || alive.deathTime > 0;
			living.isInvisibleToPlayer = mc.player != null && alive.isInvisibleTo(mc.player);
			living.isBaby = alive.isBaby();
			ItemStack head = alive.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
			living.headItem = head;
			living.wornHeadType = head.getItem() instanceof net.minecraft.world.item.BlockItem block
				&& block.getBlock() instanceof net.minecraft.world.level.block.AbstractSkullBlock skull ? skull.getType() : null;
			living.flint$entity(alive);
			living.walkAnimationPos = alive.walkAnimation.position(partialTick);
			living.walkAnimationSpeed = alive.walkAnimation.speed(partialTick);
			if (living instanceof HumanoidRenderState humanoid) {
				humanoid.isCrouching = alive.isCrouching();
				humanoid.swimAmount = alive.getSwimAmount(partialTick);
				humanoid.attackTime = alive.getAttackAnim(partialTick);
			}
			if (living instanceof AvatarRenderState avatar && entity instanceof AbstractClientPlayer player) {
				avatar.id = player.getId();
				avatar.skin = player.getSkin();
				cape(avatar, player, partialTick);
			}
		}
		return state;
	}

	/** The cape's three angles as 1.21.1's CapeLayer works them out (1.21.2 keeps them on the state as capeFlap, capeLean, capeLean2). */
	private static void cape(AvatarRenderState avatar, AbstractClientPlayer player, float h) {
		double d = Mth.lerp(h, player.xCloakO, player.xCloak) - Mth.lerp(h, player.xo, player.getX());
		double e = Mth.lerp(h, player.yCloakO, player.yCloak) - Mth.lerp(h, player.yo, player.getY());
		double m = Mth.lerp(h, player.zCloakO, player.zCloak) - Mth.lerp(h, player.zo, player.getZ());
		float n = Mth.rotLerp(h, player.yBodyRotO, player.yBodyRot);
		double o = Mth.sin(n * (float) (Math.PI / 180.0));
		double p = -Mth.cos(n * (float) (Math.PI / 180.0));
		float flap = Mth.clamp((float) e * 10.0F, -6.0F, 32.0F);
		float lean = Mth.clamp((float) (d * o + m * p) * 100.0F, 0.0F, 150.0F);
		float lean2 = Mth.clamp((float) (d * p - m * o) * 100.0F, -20.0F, 20.0F);
		float bob = Mth.lerp(h, player.oBob, player.bob);
		flap += Mth.sin(Mth.lerp(h, player.walkDistO, player.walkDist) * 6.0F) * 32.0F * bob;
		if (player.isCrouching()) {
			flap += 25.0F;
		}
		avatar.capeFlap = flap;
		avatar.capeLean = lean;
		avatar.capeLean2 = lean2;
	}
}
