package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Animations;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animations: before an arm's held item is drawn, Not Enough Animations' held items get their turn (a map's picture, a
 * swinging lantern, nothing in bed, the other hand's item hidden while a bow is drawn). The arguments are taken by type,
 * so the hook fits every version's signature (1.21.10's has no item stack).
 */
@Mixin(ItemInHandLayer.class)
abstract class ItemInHandLayerMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void flint$heldItem(CallbackInfo callback, @Local(argsOnly = true) ArmedEntityRenderState state, @Local(argsOnly = true) HumanoidArm arm,
		@Local(argsOnly = true) PoseStack pose, @Local(argsOnly = true) SubmitNodeCollector collector, @Local(argsOnly = true) int light) {
		if (Mc263Animations.heldItem(state, ((RenderLayer<?, ?>) (Object) this).getParentModel(), arm, pose, collector, light)) {
			callback.cancel();
		}
	}
}
