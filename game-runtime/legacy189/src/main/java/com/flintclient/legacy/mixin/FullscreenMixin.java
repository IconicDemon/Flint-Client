package com.flintclient.legacy.mixin;

import com.flintclient.legacy.BorderlessWindow189;
import net.minecraft.client.Minecraft;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flint's Borderless fullscreen (BorderlessWindow189): Minecraft's switches to fullscreen go through it. In borderless mode the
 * fullscreen display mode isn't picked (no mode change), and the game takes the borderless window's size, as Minecraft's own
 * resize check skips fullscreen windows.
 */
@Mixin(Minecraft.class)
public abstract class FullscreenMixin {
	@Shadow
	public int width;
	@Shadow
	public int height;

	@Shadow
	private void resize(int width, int height) {
		throw new AssertionError();
	}

	@Redirect(method = "toggleFullscreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;updateDisplayMode()V"))
	private void flint$displayMode(Minecraft mc) throws LWJGLException {
		if (!BorderlessWindow189.wanted()) {
			((MinecraftDisplayModeInvoker) mc).flint$updateDisplayMode();
		}
	}

	@Redirect(method = "toggleFullscreen", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setFullscreen(Z)V", remap = false))
	private void flint$toggle(boolean fullscreen) throws LWJGLException {
		BorderlessWindow189.setFullscreen(fullscreen);
		if (BorderlessWindow189.active()) {
			flint$takeWindowSize();
		}
	}

	@Redirect(method = "initDisplayMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setFullscreen(Z)V", remap = false))
	private void flint$start(boolean fullscreen) throws LWJGLException {
		BorderlessWindow189.atStart(fullscreen);
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void flint$startBorderless(CallbackInfo ci) {
		if (BorderlessWindow189.tick()) {
			flint$takeWindowSize();
		}
	}

	private void flint$takeWindowSize() {
		width = Math.max(1, Display.getWidth());
		height = Math.max(1, Display.getHeight());
		resize(width, height);
	}
}
