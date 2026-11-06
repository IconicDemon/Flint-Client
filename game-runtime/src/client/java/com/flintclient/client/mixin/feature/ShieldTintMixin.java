package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Shield Status: the shield (and its glint) drawn in its tint while your held shield is drawn; Minecraft's white otherwise. */
@Mixin(ShieldSpecialRenderer.class)
abstract class ShieldTintMixin {
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V", at = @org.spongepowered.asm.mixin.injection.At(value = "CONSTANT", args = "intValue=-1"), require = 0)
	private int flint$tint(int white) {
		return Mc263LookMods.shieldColor(white);
	}
}
