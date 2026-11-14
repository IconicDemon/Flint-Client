package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Cosmetics;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wings that say {@code hides_cape} hide the cape while they are worn (any cape: Minecraft's, Flint's, a cape mod's). */
@Mixin(CapeLayer.class)
abstract class CapeLayerCosmeticsMixin {
	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
		at = @At("HEAD"), cancellable = true)
	private void flint$hiddenByWings(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot,
		CallbackInfo callback) {
		if (Mc263Cosmetics.hidesCape(state)) {
			callback.cancel();
		}
	}
}
