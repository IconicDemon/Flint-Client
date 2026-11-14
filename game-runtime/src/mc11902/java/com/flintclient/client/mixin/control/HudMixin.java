package com.flintclient.client.mixin.control;

import com.flintclient.client.compat.DeltaTracker;
import com.flintclient.client.compat.GuiGraphics;
import com.flintclient.client.control.ControlCenter;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws Flint's HUD as part of Minecraft's (Minecraft 1.19.2). Its effect icons are hidden inside their own method (HudFeatureMixin).
 *
 * <p>Flint's HUD is drawn just before chat, so it sits above the hotbar, bars and scoreboard and under chat and the tab
 * list. 1.19.2 draws its whole HUD in one render method: the hook is right after the scoreboard, before Minecraft sets up
 * the chat's blending and moves the pose down for it, so Flint's HUD draws with the HUD's own pose and Minecraft's state for
 * the chat follows it. It is inside the part F1 hides, as the chat is.
 */
@Mixin(Gui.class)
abstract class HudMixin {
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableBlend()V"),
		slice = @Slice(from = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Gui;displayScoreboardSidebar(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/scores/Objective;)V"),
			to = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V", args = "ldc=chat")))
	private void flint$hudBeforeChat(PoseStack pose, float partialTick, CallbackInfo callback) {
		ControlCenter.renderHud(GuiGraphics.of(pose), DeltaTracker.GAME);
	}
}
