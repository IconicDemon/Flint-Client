package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263MoreMods;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Totem on Minecraft 1.21.1: the big totem on screen at its own size and place. 1.21.1 draws it in GUI pixels from the
 * screen's middle (later versions in view space), so the mod's offsets (view units, where 1.2 is the screen's height) are
 * turned into GUI pixels before Minecraft sizes the item.
 */
@Mixin(GameRenderer.class)
abstract class TotemPopMixin {
	@WrapOperation(method = "renderItemActivationAnimation", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
	private void flint$popPlace(PoseStack pose, float x, float y, float z, Operation<Void> original) {
		PoseStack probe = new PoseStack();
		if (Mc263MoreMods.popStart(probe)) {
			Matrix4f m = probe.last().pose();
			float unit = Minecraft.getInstance().getWindow().getGuiScaledHeight() / 1.2F;
			float scale = m.m00();
			pose.translate(m.m30() * unit, -m.m31() * unit, 0.0F);
			original.call(pose, x * scale, y * scale, z * scale);
			return;
		}
		original.call(pose, x, y, z);
	}
}
