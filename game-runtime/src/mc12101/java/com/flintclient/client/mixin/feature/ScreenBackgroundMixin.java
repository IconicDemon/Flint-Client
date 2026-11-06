package com.flintclient.client.mixin.feature;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint's screens are written for 1.21.6 and later, where a screen's background is drawn before its render() (by
 * renderWithTooltipAndSubtitles), not by Screen.render itself. Minecraft 1.21.1's Screen.render draws the background, so a
 * Flint screen that draws its own render() would have none: for Flint's screens (and only those) the background moves to
 * the start of renderWithTooltip, as on the later versions, and Screen.render leaves it out.
 */
@Mixin(Screen.class)
abstract class ScreenBackgroundMixin {
	@Unique
	private boolean flint$ownScreen() {
		return getClass().getName().startsWith("com.flintclient.");
	}

	@Inject(method = "renderWithTooltip", at = @At("HEAD"))
	private void flint$background(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo callback) {
		if (flint$ownScreen()) {
			((Screen) (Object) this).renderBackground(graphics, mouseX, mouseY, partialTick);
		}
	}

	@WrapWithCondition(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/Screen;renderBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
	private boolean flint$notTwice(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		return !flint$ownScreen();
	}
}
