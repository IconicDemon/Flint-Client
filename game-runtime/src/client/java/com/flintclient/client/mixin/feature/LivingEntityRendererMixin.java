package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nametags: your own nametag in third person (vanilla never shows the camera entity's). */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
	@ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"))
	private boolean flint$ownName(boolean show, LivingEntity entity, double distanceToCameraSq) {
		return com.flintclient.client.control.platform.Mc263Nametags.living(entity, Mc263Features.showOwnName(entity, show));
	}
}
