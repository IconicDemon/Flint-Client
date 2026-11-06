package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Items;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item Physics and 2D Items: how a dropped item is lifted, turned and which model it's drawn with. */
@Mixin(ItemEntityRenderer.class)
abstract class ItemEntityRendererMixin {
	@Shadow
	@Final
	private ItemModelResolver itemModelResolver;

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
		at = @At("TAIL"))
	private void flint$extract(ItemEntity entity, ItemEntityRenderState state, float partialTicks, CallbackInfo callback) {
		Mc263Items.extract(entity, state, itemModelResolver);
		com.flintclient.client.control.platform.Mc263MoreMods.extractDropped(entity, state);
		com.flintclient.client.control.platform.Mc263LootBeams.extract(entity, state);
	}

	/** Loot Beams' beam and name with the item; Item Despawn: a flashing item skips its hidden frames. */
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At("HEAD"), cancellable = true)
	private void flint$despawnFlash(ItemEntityRenderState state, PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
		CameraRenderState camera, CallbackInfo callback) {
		// Loot Beams first: a beam stays while Item Despawn flashes the item.
		com.flintclient.client.control.platform.Mc263LootBeams.submit(state, pose, collector, camera);
		if (com.flintclient.client.control.platform.Mc263MoreMods.droppedHidden(state)) {
			callback.cancel();
		}
	}

	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void flint$lift(PoseStack pose, float x, float y, float z, Operation<Void> original, @Local(argsOnly = true) ItemEntityRenderState state) {
		if (!Mc263Items.lift(pose, state)) {
			original.call(pose, x, y, z);
		}
	}

	/** No turn at all: what the spin's rotation becomes when Item Physics turns the item itself. Read only, never changed. */
	@org.spongepowered.asm.mixin.Unique
	private static final Quaternionf flint$NONE = new Quaternionf();

	/**
	 * Minecraft 26.1 turns the item with pose.mulPose(Axis.YP.rotation(spin)): the spin is read where its rotation is made.
	 * Item Physics' own turn goes onto the pose there and the rotation becomes none; the item's scale is uniform, so it may
	 * come before the rotation is applied.
	 */
	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/math/Axis;rotation(F)Lorg/joml/Quaternionf;"))
	private Quaternionf flint$turn(Axis axis, float spin, Operation<Quaternionf> original, @Local(argsOnly = true) ItemEntityRenderState state,
		@Local(argsOnly = true) PoseStack pose, @Local(argsOnly = true) CameraRenderState camera) {
		Quaternionf rotation = Mc263Items.turn(pose, state, spin, camera) ? flint$NONE : original.call(axis, spin);
		com.flintclient.client.control.platform.Mc263MoreMods.droppedScale(pose, state);
		return rotation;
	}
}
