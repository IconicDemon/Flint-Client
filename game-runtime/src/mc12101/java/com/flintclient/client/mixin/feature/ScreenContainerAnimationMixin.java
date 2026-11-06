package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Inventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory's open and close animations (Minecraft 1.21.1): the container moves (after the background, around everything
 * else the screen draws), and the pointer it is drawn with follows it. 1.21.1 draws a screen through renderWithTooltip.
 */
@Mixin(Screen.class)
abstract class ScreenContainerAnimationMixin {
	@Inject(method = "renderBackground", at = @At("RETURN"))
	private void flint$containerMoves(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		if ((Object) this instanceof AbstractContainerScreen<?>) {
			Mc263Inventory.animationBegin(graphics, (Screen) (Object) this);
		}
	}

	@WrapOperation(method = "renderWithTooltip", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/Screen;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
	private void flint$containerPointer(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick, Operation<Void> original) {
		original.call(screen, graphics, (int) Math.floor(Mc263Inventory.pointer(screen, mouseX, false)),
			(int) Math.floor(Mc263Inventory.pointer(screen, mouseY, true)), partialTick);
	}

	@Inject(method = "renderWithTooltip", at = @At("RETURN"))
	private void flint$containerDone(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		Mc263Inventory.animationEnd(graphics);
	}
}
