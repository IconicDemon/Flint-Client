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
	}

	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V"))
	private void flint$lift(PoseStack pose, float x, float y, float z, Operation<Void> original, @Local(argsOnly = true) ItemEntityRenderState state) {
		if (!Mc263Items.lift(pose, state)) {
			original.call(pose, x, y, z);
		}
	}

	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotate(Lcom/mojang/math/Axis;F)V"))
	private void flint$turn(PoseStack pose, Axis axis, float spin, Operation<Void> original, @Local(argsOnly = true) ItemEntityRenderState state,
		@Local(argsOnly = true) CameraRenderState camera) {
		if (!Mc263Items.turn(pose, state, spin, camera)) {
			original.call(pose, axis, spin);
		}
	}
}
