package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.compat.gui.HudParts;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Crosshair and Attack Indicator on Minecraft 1.19.2: vanilla's crosshair left out while Flint draws its own, and vanilla's
 * attack indicator while the Attack Indicator module draws one (where and when vanilla draws them). 1.19.2 blits the
 * crosshair and the indicator from icons.png, which Gui.render binds once, under an inverting blend renderCrosshair set
 * itself: both are set again after Flint's drawing, for the indicator that follows.
 */
@Mixin(Gui.class)
abstract class HudCrosshairMixin {
	@WrapWithCondition(method = "renderCrosshair", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private boolean flint$crosshair(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h) {
		boolean own = Mc263Overlay.crosshair(GuiGraphics.of(pose));
		HudParts.icons();
		RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
			GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
		return !own;
	}

	/** The indicator's full icon (ordinal 1), its background (2) and its progress (3). */
	@WrapWithCondition(method = "renderCrosshair", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private boolean flint$vanillaIndicatorFull(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@WrapWithCondition(method = "renderCrosshair", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private boolean flint$vanillaIndicatorBack(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@WrapWithCondition(method = "renderCrosshair", at = @At(value = "INVOKE", ordinal = 3,
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private boolean flint$vanillaIndicatorProgress(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}
}
