package com.flintclient.legacy.mixin.cosmetics;

import com.flintclient.legacy.cosmetics.CosmeticsLayer189;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player renderers (wide and slim arms) draw the 3D cosmetics after Minecraft's own layers (CosmeticsLayer189). */
@Mixin(PlayerRenderer.class)
abstract class PlayerRendererCosmeticsMixin {
	@Inject(method = "<init>(Lnet/minecraft/client/render/entity/EntityRenderDispatcher;Z)V", at = @At("TAIL"))
	private void flint$cosmeticsLayer(EntityRenderDispatcher dispatcher, boolean thinArms, CallbackInfo ci) {
		PlayerRenderer self = (PlayerRenderer) (Object) this;
		((LivingEntityRendererLayers) self).flint$addLayer(new CosmeticsLayer189(self.getModel()));
	}
}
