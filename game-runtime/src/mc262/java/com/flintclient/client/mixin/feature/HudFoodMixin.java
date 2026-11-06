package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Food;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Saturation: where Minecraft really draws each drumstick and heart this frame (they shake when you're starving or low),
 * so the overlays sit exactly on them; and the exhaustion bar under the drumsticks, before they're drawn.
 */
@Mixin(Hud.class)
abstract class HudFoodMixin {
	@Inject(method = "extractFood", at = @At("HEAD"))
	private void flint$foodUnder(GuiGraphicsExtractor graphics, Player player, int yLineBase, int xRight, CallbackInfo callback) {
		Mc263Food.beforeFood(graphics, player, yLineBase, xRight);
	}

	/** Each drumstick's empty icon: the first drawn at its place. */
	@WrapOperation(method = "extractFood", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
		ordinal = 0))
	private void flint$foodIcon(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
		Operation<Void> original) {
		Mc263Food.foodIcon(x, y);
		original.call(graphics, pipeline, sprite, x, y, w, h);
	}

	@Inject(method = "extractHearts", at = @At("HEAD"))
	private void flint$heartsStart(CallbackInfo callback) {
		Mc263Food.heartsStart();
	}

	/** Every heart sprite (container first, then what fills it), at its place. */
	@WrapOperation(method = "extractHeart", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void flint$heartIcon(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
		Operation<Void> original) {
		Mc263Food.heartIcon(x, y);
		original.call(graphics, pipeline, sprite, x, y, w, h);
	}
}
