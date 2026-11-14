package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.compat.state.AvatarRenderState;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wings that ask for it hide the wearer's cape (1.21.1's cape layer). */
@Mixin(CapeLayer.class)
abstract class CapeLayerCosmeticsMixin {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
		at = @At("HEAD"), cancellable = true)
	private void flint$hiddenByWings(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
		float limbSwingAmount, float partialTick, float ageInTicks, float headYaw, float headPitch, CallbackInfo callback) {
		if (RenderStates.peek(player) instanceof AvatarRenderState state && Mc263Cosmetics.hidesCape(state)) {
			callback.cancel();
		}
	}
}
