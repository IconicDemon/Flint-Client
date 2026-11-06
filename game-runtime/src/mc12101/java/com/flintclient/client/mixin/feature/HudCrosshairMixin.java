package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Crosshair and Attack Indicator on Minecraft 1.21.1: vanilla's crosshair sprite left out while Flint draws its own, and
 * vanilla's attack indicator while the Attack Indicator module draws one (where and when vanilla draws them). 1.21.1 draws the
 * crosshair under an inverting blend it set itself; it is set again after Flint's drawing for the indicator that follows.
 */
@Mixin(Gui.class)
abstract class HudCrosshairMixin {
	@WrapWithCondition(method = "renderCrosshair", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private boolean flint$crosshair(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int w, int h) {
		boolean own = Mc263Overlay.crosshair(graphics);
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
			GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
		return !own;
	}

	@WrapWithCondition(method = "renderCrosshair", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private boolean flint$vanillaIndicatorFull(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@WrapWithCondition(method = "renderCrosshair", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private boolean flint$vanillaIndicatorBack(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@WrapWithCondition(method = "renderCrosshair", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIIIIIII)V"))
	private boolean flint$vanillaIndicatorProgress(GuiGraphics graphics, ResourceLocation sprite, int sw, int sh, int u, int v, int x, int y, int w,
		int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}
}
