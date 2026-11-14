package com.flintclient.client.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.renderer.GameRenderer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Instant Swap ({@link InstantSwap#framesHeld}) on Minecraft 1.19: no frame is drawn or presented while the game hands over
 * (the window keeps showing its last frame, so leaving the world or server under it is never seen) or while a standby game
 * waits hidden at its menu (it costs the player's game no GPU time). 1.19 draws and presents in {@code runTick} (26.3 has a
 * renderFrame of its own): the clear, the game's and the toasts' drawing, the blit to the window and the buffer swap are left
 * out, while the window's events are still taken in. Ticks, input and the world's saving go on as usual.
 */
@Mixin(Minecraft.class)
abstract class SwapFrameMixin {
	@WrapWithCondition(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"))
	private boolean flint$noClear(int mask, boolean checkError) {
		return !InstantSwap.framesHeld();
	}

	@WrapWithCondition(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V"))
	private boolean flint$noDraw(GameRenderer renderer, float partialTick, long nanos, boolean renderLevel) {
		return !InstantSwap.framesHeld();
	}

	@WrapWithCondition(method = "runTick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;render(Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private boolean flint$noToasts(ToastComponent toasts, PoseStack pose) {
		return !InstantSwap.framesHeld();
	}

	@WrapWithCondition(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
	private boolean flint$noBlit(RenderTarget target, int width, int height) {
		return !InstantSwap.framesHeld();
	}

	@Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
	private void flint$noPresent(Window window) {
		if (InstantSwap.framesHeld()) {
			// The events still come in (the window answers the system), and work queued for the render thread still runs; the
			// buffers aren't swapped, so the last frame stays on screen.
			GLFW.glfwPollEvents();
			com.mojang.blaze3d.systems.RenderSystem.replayQueue();
		} else {
			window.updateDisplay();
			// A frame was drawn and presented: the new game counts them before it lets the still go (no white first frame).
			if (InstantSwap.comingIn()) {
				// Counted once Windows has composed it (on screen for real), not merely handed to the driver.
				com.flintclient.client.control.platform.Mc263SwapEngine.waitComposed();
			}
			InstantSwap.framePresented();
		}
	}
}
