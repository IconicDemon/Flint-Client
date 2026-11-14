package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.PanelBlur;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Minecraft's menu blur, kept to the Right Shift window's panel while it asks for that ({@link PanelBlur}): the GUI renderer's one
 * {@code processBlurEffect} a frame. A condition wrapper, so nothing is allocated per frame (as {@code HudMixin}); without a clip it
 * says yes and Minecraft blurs as always. Optional: if another mod takes the call, the menu's blur covers the screen as before.
 */
@Mixin(GuiRenderer.class)
abstract class PanelBlurMixin {
	@WrapWithCondition(method = "draw", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"))
	private boolean flint$panelBlur(GameRenderer renderer) {
		return PanelBlur.blurWhole(renderer);
	}
}
