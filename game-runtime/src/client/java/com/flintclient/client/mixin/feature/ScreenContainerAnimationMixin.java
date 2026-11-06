package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Inventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory's open/close animations: once a container screen's dimmed backdrop is drawn, the container itself (and what
 * is drawn over it) moves and scales, and the pointer it's drawn for follows it.
 */
@Mixin(Screen.class)
abstract class ScreenContainerAnimationMixin {
	@Inject(method = "extractBackground", at = @At("RETURN"))
	private void flint$containerMoves(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		if ((Object) this instanceof AbstractContainerScreen<?>) {
			Mc263Inventory.animationBegin(graphics, (Screen) (Object) this);
		}
	}

	@WrapOperation(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
	private void flint$containerPointer(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
		Operation<Void> original) {
		original.call(screen, graphics, (int) Math.floor(Mc263Inventory.pointer(screen, mouseX, false)),
			(int) Math.floor(Mc263Inventory.pointer(screen, mouseY, true)), partialTick);
	}

	@Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
	private void flint$containerDone(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		Mc263Inventory.animationEnd(graphics);
	}
}
