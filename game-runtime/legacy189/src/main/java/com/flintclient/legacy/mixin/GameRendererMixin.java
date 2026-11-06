package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom narrows the world's field of view (not the hand's) and slows the mouse; Brightness builds the light map with its own
 * gamma; Motion Blur blurs the terrain before the entities and the finished world before the hand.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@org.spongepowered.asm.mixin.Unique
	private static long lastFrameNanos;

	@Inject(method = "getFov(FZ)F", at = @At("RETURN"), cancellable = true)
	private void flint$zoom(float tickDelta, boolean world, CallbackInfoReturnable<Float> cir) {
		if (world && Flint.ready() && Flint.modules().zoom.enabled()) {
			float factor = Flint.modules().zoom.factor();
			if (factor != 1) {
				cir.setReturnValue(cir.getReturnValue() / factor);
			}
		}
	}

	/** Over the screen just drawn: what Flint says about the files put into the game's folders ({@code GameFolders}). */
	@Inject(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/Screen;render(IIF)V", shift = At.Shift.AFTER))
	private void flint$folderNotice(float tickDelta, long startTime, CallbackInfo ci) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.screen != null) {
			com.flintclient.legacy.GameFolders.drawNotice(mc, mc.screen);
		}
	}

	/** The frame is drawn (the world, the HUD, any screen): Flint Friends' notices go over all of it ({@code FriendNotices189}). */
	@Inject(method = "render(FJ)V", at = @At("TAIL"))
	private void flint$friendNotices(float tickDelta, long startTime, CallbackInfo ci) {
		com.flintclient.legacy.FriendNotices189.draw(net.minecraft.client.Minecraft.getInstance());
	}

	/** A new frame: the shared screens' animations and mask atlas count frames from here ({@code ui.FlintFrame}). */
	@Inject(method = "render(FJ)V", at = @At("HEAD"))
	private void flint$frame(float tickDelta, long startTime, CallbackInfo ci) {
		com.flintclient.client.ui.FlintFrame.advance();
		com.flintclient.legacy.HudTiming.frame++;
		// The frame times the FPS module reads (shared FrameStats, recorded only while something reads them), and how far this frame
		// is between two ticks.
		long now = System.nanoTime();
		if (lastFrameNanos != 0) {
			com.flintclient.client.perf.FrameStats.record(now - lastFrameNanos);
		}
		lastFrameNanos = now;
		com.flintclient.client.platform.HudData.setPartialTick(tickDelta);
	}

	@Redirect(method = "render(FJ)V", at = @At(value = "FIELD", target = "Lnet/minecraft/client/options/GameOptions;mouseSensitivity:F"))
	private float flint$sensitivity(GameOptions options) {
		float own = options.mouseSensitivity;
		if (Flint.ready() && Flint.modules().zoom.active()) {
			return Flint.modules().zoom.sensitivity(own, Flint.modules().zoom.factor());
		}
		return own;
	}

	@Redirect(method = "updateLightMap(F)V", at = @At(value = "FIELD", target = "Lnet/minecraft/client/options/GameOptions;gamma:F"))
	private float flint$gamma(GameOptions options) {
		return Flint.ready() ? Flint.modules().brightness.gamma(options.gamma) : options.gamma;
	}

	/** Waypoints: the camera's matrices for the labels, and the waypoints' blocks, after the translucent blocks and before the hand. */
	@Inject(method = "render(IFJ)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V",
		args = "ldc=hand"))
	private void flint$waypoints(int pass, float tickDelta, long limit, CallbackInfo ci) {
		if (Flint.ready() && Flint.modules().waypoints.enabled()) {
			try {
				com.flintclient.legacy.waypoints.WaypointRenderer.world(tickDelta);
			} catch (RuntimeException e) {
				Flint.fault(Flint.modules().waypoints, e);
			}
		}
	}

	/**
	 * Motion Blur's early pass: after the solid and cutout terrain, before any entity (the split Natural Motion Blur makes, so
	 * players and mobs aren't blurred by your own movement).
	 */
	@Inject(method = "render(IFJ)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V",
		args = "ldc=entities"))
	private void flint$motionBlurEarly(int pass, float tickDelta, long limit, CallbackInfo ci) {
		if (Flint.ready() && Flint.modules().motionBlur.enabled()) {
			try {
				Flint.modules().motionBlur.early(pass, tickDelta);
			} catch (RuntimeException e) {
				Flint.fault(Flint.modules().motionBlur, e);
			}
		}
	}

	/** Motion Blur's late pass: the finished world, before the depth is cleared for the hand (the hand and the HUD stay sharp). */
	@Inject(method = "render(IFJ)V", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V",
		args = "ldc=hand"))
	private void flint$motionBlur(int pass, float tickDelta, long limit, CallbackInfo ci) {
		if (Flint.ready() && Flint.modules().motionBlur.enabled()) {
			try {
				Flint.modules().motionBlur.late(pass, tickDelta);
			} catch (RuntimeException e) {
				Flint.fault(Flint.modules().motionBlur, e);
			}
		}
	}
}
