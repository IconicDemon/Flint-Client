package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Food;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Saturation: where Minecraft really draws each drumstick and heart this frame (they shake when you're starving or low),
 * so the overlays sit exactly on them; and the exhaustion bar under the drumsticks, before they're drawn. Minecraft 1.21.11
 * draws the HUD in Gui (renderFood, renderHearts, renderHeart) and keeps the pipeline type in com.mojang.blaze3d.
 */
@Mixin(Gui.class)
abstract class HudFoodMixin {
	@Inject(method = "renderFood", at = @At("HEAD"))
	private void flint$foodUnder(GuiGraphics graphics, Player player, int yLineBase, int xRight, CallbackInfo callback) {
		Mc263Food.beforeFood(graphics, player, yLineBase, xRight);
	}

	/** Each drumstick's empty icon: the first drawn at its place. */
	@WrapOperation(method = "renderFood", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
		ordinal = 0))
	private void flint$foodIcon(GuiGraphics graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
		Operation<Void> original) {
		Mc263Food.foodIcon(x, y);
		original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	@Inject(method = "renderHearts", at = @At("HEAD"))
	private void flint$heartsStart(CallbackInfo callback) {
		Mc263Food.heartsStart();
	}

	/** Every heart sprite (container first, then what fills it), at its place. */
	@WrapOperation(method = "renderHeart", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void flint$heartIcon(GuiGraphics graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
		Operation<Void> original) {
		Mc263Food.heartIcon(x, y);
		original.call(graphics, pipeline, sprite, x, y, w, h);
	}
}
