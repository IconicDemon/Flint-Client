package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mob Overlay's Outline: an outlined mob's outline in Flint's colour (Minecraft uses its team colour). */
@Mixin(EntityRenderer.class)
abstract class OutlineColorMixin {
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getTeamColor()I"))
	private int flint$color(Entity entity, Operation<Integer> original) {
		return Mc263LookMods.outlineColor(entity, original.call(entity));
	}
}
