package com.flintclient.legacy.mixin.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.flintclient.legacy.swap.SwapWindow189;
import java.util.Queue;
import java.util.concurrent.FutureTask;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TickTimer;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.GlStateManager;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Instant Swap on 1.8.9 ({@link InstantSwap}): a standby game's display is made hidden ({@link SwapWindow189}) and windowed
 * even when the player plays fullscreen; it is capped while it loads; and while a game holds its frame
 * ({@link InstantSwap#framesHeld}: the old game handing over, or a standby waiting at its menu) nothing is drawn or presented:
 * the game loop only runs its tasks and ticks (the world is saved, the link answered) and keeps the window answering the system.
 */
@Mixin(Minecraft.class)
abstract class SwapMinecraftMixin {
	@Shadow
	public int width;
	@Shadow
	public int height;
	@Shadow
	private TickTimer timer;
	@Shadow
	@Final
	private Queue<FutureTask<?>> tasks;
	@Shadow
	private net.minecraft.client.render.pipeline.RenderTarget renderTarget;

	@Shadow
	public abstract void tick();

	@Shadow
	public abstract void stop();

	@Inject(method = "initDisplay", at = @At("HEAD"))
	private void flint$hiddenWhileStandby(CallbackInfo ci) {
		SwapWindow189.beforeCreate(width, height);
	}

	@Redirect(method = "init", at = @At(value = "FIELD", target = "Lnet/minecraft/client/options/GameOptions;fullscreen:Z"))
	private boolean flint$windowedWhileStandby(GameOptions options) {
		return options.fullscreen && !InstantSwap.standby();
	}

	@Inject(method = "runGame", at = @At("HEAD"), cancellable = true)
	private void flint$holdFrame(CallbackInfo ci) {
		if (!InstantSwap.framesHeld()) {
			return;
		}
		ci.cancel();
		if (Display.isCreated() && Display.isCloseRequested()) {
			stop();
		}
		timer.advance();
		synchronized (tasks) {
			while (!tasks.isEmpty()) {
				// A FutureTask keeps what it throws.
				tasks.poll().run();
			}
		}
		for (int i = 0; i < timer.ticksThisFrame; i++) {
			tick();
		}
		if (!InstantSwap.waiting() && GLX.useFbo() && renderTarget != null && Display.isCreated()) {
			// The old game handing over: its last frame (still in Minecraft's framebuffer: nothing is drawn into it while held)
			// is put on the window again each time, so nothing the system does to the window (a move, a repaint) can blank it.
			renderTarget.unbindWrite();
			GlStateManager.pushMatrix();
			renderTarget.draw(width, height);
			GlStateManager.popMatrix();
			Display.update();
		} else {
			// A standby game waiting hidden presents nothing at all (it costs the player's game no GPU time).
			Display.processMessages();
		}
		Display.sync(30);
	}

	/**
	 * The hold starting during this frame's ticks (the handover runs in a tick, and its Save and Quit opens the title screen):
	 * this frame isn't drawn either. The frame so far (the matrix pushed, Minecraft's framebuffer bound) is undone and the held
	 * frame is shown again instead, so the last picture stays the world, never the title screen fading in.
	 */
	@Inject(method = "runGame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;render(FJ)V"), cancellable = true)
	private void flint$holdBeforeDrawing(CallbackInfo ci) {
		if (!InstantSwap.framesHeld()) {
			return;
		}
		ci.cancel();
		if (renderTarget != null) {
			renderTarget.unbindWrite();
		}
		GlStateManager.popMatrix();
		if (!InstantSwap.waiting() && GLX.useFbo() && renderTarget != null && Display.isCreated()) {
			GlStateManager.pushMatrix();
			renderTarget.draw(width, height);
			GlStateManager.popMatrix();
			Display.update();
		} else {
			Display.processMessages();
		}
	}

	/**
	 * A frame really went to the screen (right after the buffer swap, Display.update in updateDisplay; a held frame never gets
	 * there): the new game counts these before it lets the still go, so the player never sees its window without a picture.
	 */
	@Inject(method = "runGame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;updateDisplay()V", shift = At.Shift.AFTER))
	private void flint$framePresented(CallbackInfo ci) {
		InstantSwap.framePresented();
	}

	@Inject(method = "getMaxFramerate", at = @At("HEAD"), cancellable = true)
	private void flint$standbyCap(CallbackInfoReturnable<Integer> cir) {
		int cap = InstantSwap.frameCap();
		if (cap > 0) {
			cir.setReturnValue(cap);
		}
	}
}
