package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nametags: named mobs and pets always named, when asked. */
@Mixin(MobRenderer.class)
abstract class MobRendererMixin {
	@ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/Mob;D)Z", at = @At("RETURN"))
	private boolean flint$always(boolean show, Mob mob, double distanceToCameraSq) {
		return Mc263Nametags.mob(mob, show);
	}
}
