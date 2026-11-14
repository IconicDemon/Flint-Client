package com.flintclient.legacy.mixin.cosmetics;

import com.flintclient.legacy.cosmetics.OwnSkin189;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.resource.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The wardrobe's Apply (OwnSkin189): your player wears the new skin and its arm model at once, until the next join. */
@Mixin(ClientPlayerEntity.class)
abstract class OwnSkinMixin {
	@Inject(method = "getSkinTextureLocation()Lnet/minecraft/resource/Identifier;", at = @At("HEAD"), cancellable = true)
	private void flint$ownSkin(CallbackInfoReturnable<Identifier> cir) {
		Identifier skin = OwnSkin189.skin((ClientPlayerEntity) (Object) this);
		if (skin != null) {
			cir.setReturnValue(skin);
		}
	}

	@Inject(method = "getModelType", at = @At("HEAD"), cancellable = true)
	private void flint$ownModel(CallbackInfoReturnable<String> cir) {
		String model = OwnSkin189.model((ClientPlayerEntity) (Object) this);
		if (model != null) {
			cir.setReturnValue(model);
		}
	}
}
