package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.compat.gui.HudParts;
import com.flintclient.client.control.platform.Mc263Food;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Saturation: where Minecraft really draws each drumstick and heart this frame (they shake when you're starving or low),
 * so the overlays sit exactly on them; and the exhaustion bar under the drumsticks, before they're drawn. Minecraft 1.19.2
 * draws the food row inline in renderPlayerHealth (its "food" profiler section, blitting from icons.png), the hearts in
 * renderHearts and renderHeart.
 */
@Mixin(Gui.class)
abstract class HudFoodMixin {
	@Shadow
	private int screenWidth;
	@Shadow
	private int screenHeight;

	@Shadow
	private Player getCameraPlayer() {
		throw new AssertionError();
	}

	/** As the food row's section starts (after Autohide HUD placed it); icons.png is bound again for the drumsticks after. */
	@Inject(method = "renderPlayerHealth", at = @At(value = "INVOKE_STRING", shift = At.Shift.AFTER,
		target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=food"))
	private void flint$foodUnder(PoseStack pose, CallbackInfo callback) {
		Player player = getCameraPlayer();
		if (player == null || HudParts.food == HudParts.SKIP) {
			return;
		}
		Mc263Food.beforeFood(GuiGraphics.of(pose), player, screenHeight - 39, screenWidth / 2 + 91);
		HudParts.icons();
	}

	/** Each drumstick's empty icon: the first drawn at its place. */
	@WrapOperation(method = "renderPlayerHealth", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"),
		slice = @Slice(from = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
			args = "ldc=food"), to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
			args = "ldc=air")))
	private void flint$foodIcon(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h, Operation<Void> original) {
		Mc263Food.foodIcon(x, y);
		original.call(hud, pose, x, y, u, v, w, h);
	}

	@Inject(method = "renderHearts", at = @At("HEAD"))
	private void flint$heartsStart(CallbackInfo callback) {
		Mc263Food.heartsStart();
	}

	/** Every heart icon (container first, then what fills it), at its place. */
	@WrapOperation(method = "renderHeart", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Gui;blit(Lcom/mojang/blaze3d/vertex/PoseStack;IIIIII)V"))
	private void flint$heartIcon(Gui hud, PoseStack pose, int x, int y, int u, int v, int w, int h, Operation<Void> original) {
		Mc263Food.heartIcon(x, y);
		original.call(hud, pose, x, y, u, v, w, h);
	}
}
