package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Shields;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.object.equipment.ShieldModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A shield held by someone wearing a Flint shield is drawn as that shield ({@link Mc263Shields}). */
@Mixin(ShieldSpecialRenderer.class)
abstract class ShieldSpecialRendererCosmeticsMixin {
	@Shadow
	@Final
	private ShieldModel model;

	@Inject(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
		at = @At("HEAD"), cancellable = true)
	private void flint$flintShield(DataComponentMap components, ItemDisplayContext context, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil,
		int outline, CallbackInfo callback) {
		if (Mc263Shields.submit(model, pose, collector, light, overlay, outline)) {
			callback.cancel();
		}
	}
}
