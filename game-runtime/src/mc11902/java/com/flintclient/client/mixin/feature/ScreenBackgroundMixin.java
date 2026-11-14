package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.ScreenFrame;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Flint's screens are written for 1.20.2 and later, where a screen's background is drawn before its render() and a tooltip it
 * asks for after it. Minecraft 1.19.2 draws the screen in one call from GameRenderer: around that call, {@link ScreenFrame}
 * draws a Flint screen's background first and the asked-for tooltip last (1.19.2's own screens draw their background
 * themselves, as before).
 */
@Mixin(GameRenderer.class)
abstract class ScreenBackgroundMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/Screen;render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V"))
	private void flint$frame(Screen screen, PoseStack pose, int mouseX, int mouseY, float partialTick, Operation<Void> original) {
		ScreenFrame.begin(screen, pose, mouseX, mouseY, partialTick);
		original.call(screen, pose, mouseX, mouseY, partialTick);
		ScreenFrame.end(pose);
	}
}
