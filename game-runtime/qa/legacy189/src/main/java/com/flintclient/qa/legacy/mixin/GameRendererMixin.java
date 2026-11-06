package com.flintclient.qa.legacy.mixin;

import com.flintclient.qa.legacy.FlintQa;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts and times frames (for the fps and frames steps) and hands an open screen QA's pointer instead of the real mouse while a tour moves it. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "render(FJ)V", at = @At("HEAD"))
	private void flintqa$countFrame(float tickDelta, long startTime, CallbackInfo ci) {
		FlintQa.frames++;
		com.flintclient.qa.legacy.LegacyFrames.frame();
		FlintQa.turnFrame(minecraft);
	}

	@Redirect(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getX()I", remap = false))
	private int flintqa$mouseX() {
		return FlintQa.ENABLED && FlintQa.mouseX >= 0 ? FlintQa.mouseX : Mouse.getX();
	}

	@Redirect(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getY()I", remap = false))
	private int flintqa$mouseY() {
		// LWJGL counts from the bottom of the window.
		return FlintQa.ENABLED && FlintQa.mouseY >= 0 ? minecraft.height - 1 - FlintQa.mouseY : Mouse.getY();
	}
}
