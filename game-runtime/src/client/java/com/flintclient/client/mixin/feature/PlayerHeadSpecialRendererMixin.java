package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 3D Skins: a player head item (in a slot or in hand) is always close enough for its 3D hat. */
@Mixin(PlayerHeadSpecialRenderer.class)
abstract class PlayerHeadSpecialRendererMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
		at = @At("HEAD"))
	private void flint$itemStart(CallbackInfo callback) {
		Mc263Skins.headItem(true);
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
		at = @At("RETURN"))
	private void flint$itemEnd(CallbackInfo callback) {
		Mc263Skins.headItem(false);
	}
}
