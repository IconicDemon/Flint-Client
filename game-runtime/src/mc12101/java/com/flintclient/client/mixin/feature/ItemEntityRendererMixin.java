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
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Physics, 2D Items, Item Despawn, UHC Overlay's dropped-item size and Loot Beams on Minecraft 1.21.1, which draws a dropped item
 * straight from the entity in render: the item's state (compat.state) is filled there as later versions extract it, then
 * Minecraft's lift and spin give way to Flint's (Mc263Items) and the item is drawn with its 2D icon's model when one is chosen.
 */
@Mixin(ItemEntityRenderer.class)
abstract class ItemEntityRendererMixin {
	/** No turn at all: what the spin's rotation becomes when Item Physics turns the item itself. Read only, never changed. */
	@Unique
	private static final Quaternionf flint$NONE = new Quaternionf();

	/** The dropped item being drawn (render thread), or null outside render. */
	@Unique
	private static @Nullable ItemEntityRenderState flint$state;

	@Inject(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"), cancellable = true)
	private void flint$extract(ItemEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
		CallbackInfo callback) {
		flint$state = null;
		if (!(RenderStates.fill(entity, partialTick, light, null) instanceof ItemEntityRenderState state)) {
			return;
		}
		state.ageInTicks = entity.getAge() + partialTick;
		state.bobOffset = entity.bobOffs;
		ItemModelResolver.INSTANCE.updateForNonLiving(state.item, entity.getItem(), ItemDisplayContext.GROUND, entity);
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

	@Inject(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("RETURN"))
	private void flint$done(ItemEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo callback) {
		flint$state = null;
	}

	@WrapOperation(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void flint$lift(PoseStack pose, float x, float y, float z, Operation<Void> original) {
		ItemEntityRenderState state = flint$state;
		if (state == null || !Mc263Items.lift(pose, state)) {
			original.call(pose, x, y, z);
		}
	}

	/**
	 * Minecraft 1.21.1 turns the item with pose.mulPose(Axis.YP.rotation(spin)): Item Physics' own turn goes onto the pose
	 * where that rotation is made and the rotation becomes none; UHC Overlay's size follows (it is uniform).
	 */
	@WrapOperation(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotation(F)Lorg/joml/Quaternionf;"))
	private Quaternionf flint$turn(Axis axis, float spin, Operation<Quaternionf> original, @Local(argsOnly = true) PoseStack pose) {
		ItemEntityRenderState state = flint$state;
		if (state == null) {
			return original.call(axis, spin);
		}
		Quaternionf rotation = Mc263Items.turn(pose, state, spin, RenderStates.camera()) ? flint$NONE : original.call(axis, spin);
		Mc263MoreMods.droppedScale(pose, state);
		return rotation;
	}

	/** 2D Items: the item is drawn in its inventory look (the GUI display context) when Flint chose its icon. */
	@WrapOperation(method = "renderMultipleFromCount(Lnet/minecraft/client/renderer/entity/ItemRenderer;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/resources/model/BakedModel;ZLnet/minecraft/util/RandomSource;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"))
	private static void flint$icon(ItemRenderer renderer, ItemStack stack, ItemDisplayContext context, boolean left, PoseStack pose,
		MultiBufferSource buffers, int light, int overlay, BakedModel model, Operation<Void> original) {
		ItemEntityRenderState state = flint$state;
		ItemDisplayContext shown = state != null && state.item.stack == stack ? state.item.context : context;
		original.call(renderer, stack, shown, left, pose, buffers, light, overlay, model);
	}
}
