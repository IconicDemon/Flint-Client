package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.state.ItemEntityRenderState;
import com.flintclient.client.compat.state.ItemModelResolver;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.control.platform.Mc263Items;
import com.flintclient.client.control.platform.Mc263MoreMods;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Physics, 2D Items, Item Despawn, UHC Overlay's dropped-item size and Loot Beams on Minecraft 1.19.2, which draws a dropped
 * item straight from the entity in render (its copies too, in the same method): the item's state (compat.state) is filled there
 * as later versions extract it, then Minecraft's lift (render's first translate) and spin give way to Flint's (Mc263Items) and
 * the item is drawn with its 2D icon's model when one is chosen. 1.19.2 names display contexts ItemTransforms.TransformType.
 */
@Mixin(ItemEntityRenderer.class)
abstract class ItemEntityRendererMixin {
	@Unique
	private static final String RENDER = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

	/** No turn at all: what the spin's rotation becomes when Item Physics turns the item itself. Read only, never changed. */
	@Unique
	private static final Quaternion flint$NONE = new Quaternion(0.0F, 0.0F, 0.0F, 1.0F);

	/** The dropped item being drawn (render thread), or null outside render. */
	@Unique
	private static @Nullable ItemEntityRenderState flint$state;

	@Inject(method = RENDER, at = @At("HEAD"), cancellable = true)
	private void flint$extract(ItemEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
		CallbackInfo callback) {
		flint$state = null;
		if (!(RenderStates.fill(entity, partialTick, light, null) instanceof ItemEntityRenderState state)) {
			return;
		}
		state.ageInTicks = entity.getAge() + partialTick;
		state.bobOffset = entity.bobOffs;
		ItemModelResolver.INSTANCE.updateForNonLiving(state.item, entity.getItem(), ItemTransforms.TransformType.GROUND, entity);
		Mc263Items.extract(entity, state, ItemModelResolver.INSTANCE);
		Mc263MoreMods.extractDropped(entity, state);
		com.flintclient.client.control.platform.Mc263LootBeams.extract(entity, state);
		// Loot Beams first, at the item: a beam stays while Item Despawn flashes the item.
		if (state.flint$loot() != null) {
			com.flintclient.client.control.platform.Mc263LootBeams.submit(state, pose, new com.flintclient.client.compat.state.SubmitNodeCollector(buffers),
				RenderStates.camera());
		}
		// Item Despawn: a flashing item skips its hidden frames.
		if (Mc263MoreMods.droppedHidden(state)) {
			callback.cancel();
			return;
		}
		flint$state = state;
	}

	@Inject(method = RENDER, at = @At("RETURN"))
	private void flint$done(ItemEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo callback) {
		flint$state = null;
	}

	/** Minecraft's lift: render's first translate (the copies' offsets come after the spin). */
	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", ordinal = 0, target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"))
	private void flint$lift(PoseStack pose, double x, double y, double z, Operation<Void> original) {
		ItemEntityRenderState state = flint$state;
		if (state == null || !Mc263Items.lift(pose, state)) {
			original.call(pose, x, y, z);
		}
	}

	/**
	 * Minecraft 1.19.2 turns the item with pose.mulPose(Vector3f.YP.rotation(spin)): Item Physics' own turn goes onto the pose
	 * where that rotation is made and the rotation becomes none; UHC Overlay's size follows (it is uniform).
	 */
	@WrapOperation(method = RENDER, at = @At(value = "INVOKE", target = "Lcom/mojang/math/Vector3f;rotation(F)Lcom/mojang/math/Quaternion;"))
	private Quaternion flint$turn(Vector3f axis, float spin, Operation<Quaternion> original, @Local(argsOnly = true) PoseStack pose) {
		ItemEntityRenderState state = flint$state;
		if (state == null) {
			return original.call(axis, spin);
		}
		Quaternion rotation = Mc263Items.turn(pose, state, spin, RenderStates.camera()) ? flint$NONE : original.call(axis, spin);
		Mc263MoreMods.droppedScale(pose, state);
		return rotation;
	}

	/** 2D Items: the item is drawn in its inventory look (the GUI display context) when Flint chose its icon. */
	@WrapOperation(method = RENDER, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemTransforms$TransformType;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"))
	private void flint$icon(ItemRenderer renderer, ItemStack stack, ItemTransforms.TransformType context, boolean left, PoseStack pose,
		MultiBufferSource buffers, int light, int overlay, BakedModel model, Operation<Void> original) {
		ItemEntityRenderState state = flint$state;
		ItemTransforms.TransformType shown = state != null && state.item.stack == stack ? state.item.context : context;
		original.call(renderer, stack, shown, left, pose, buffers, light, overlay, model);
	}
}
