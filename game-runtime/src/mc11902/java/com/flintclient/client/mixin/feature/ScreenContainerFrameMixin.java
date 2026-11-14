package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gui.ModelView;
import com.flintclient.client.control.platform.Mc263Inventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Inventory's open and close animations (Minecraft 1.19.2), the frame's side: the screen is drawn with the pointer where it
 * is on the moving container, and the container moves back once the screen is drawn (its tooltip included, as 1.20's
 * deferred tooltip is). 1.19.2 draws the screen in one call from GameRenderer.render; ScreenContainerAnimationMixin starts
 * the move.
 */
@Mixin(GameRenderer.class)
abstract class ScreenContainerFrameMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/Screen;render(Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V"))
	private void flint$containerPointer(Screen screen, PoseStack pose, int mouseX, int mouseY, float partialTick, Operation<Void> original) {
		original.call(screen, pose, (int) Math.floor(Mc263Inventory.pointer(screen, mouseX, false)),
			(int) Math.floor(Mc263Inventory.pointer(screen, mouseY, true)), partialTick);
		if (Mc263Inventory.moving()) {
			Mc263Inventory.animationEnd(ModelView.graphics());
			ModelView.apply();
		}
	}
}
