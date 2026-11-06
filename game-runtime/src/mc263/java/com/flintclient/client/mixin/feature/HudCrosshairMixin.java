package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Crosshair and Attack Indicator: vanilla's crosshair sprite left out while Flint draws its own, and vanilla's attack
 * indicator while the Attack Indicator module draws one (where and when vanilla draws them, so first person, spectator
 * and the 3D-crosshair debug rules all still apply). Minecraft 26.3 keeps the pipeline type in com.mojang.renderpearl.
 */
@Mixin(Hud.class)
abstract class HudCrosshairMixin {
	@WrapWithCondition(method = "extractCrosshair", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private boolean flint$crosshair(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		return !Mc263Overlay.crosshair(graphics);
	}

	@WrapWithCondition(method = "extractCrosshair", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private boolean flint$vanillaIndicatorFull(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@WrapWithCondition(method = "extractCrosshair", at = @At(value = "INVOKE", ordinal = 2,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private boolean flint$vanillaIndicatorBack(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}

	@WrapWithCondition(method = "extractCrosshair", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"))
	private boolean flint$vanillaIndicatorProgress(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int sw, int sh, int u,
		int v, int x, int y, int w, int h) {
		return !Mc263Overlay.ownAttackIndicator();
	}
}
