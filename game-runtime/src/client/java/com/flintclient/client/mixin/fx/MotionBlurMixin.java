package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.MotionBlur;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Runs Flint's optional motion blur ({@link MotionBlur}) once per rendered world frame, between the world and the
 * hand: in {@code GameRenderer.renderLevel}, just before its {@code render3dHud} call. At that point the main target
 * holds the finished world image; the hand, held item and screen overlays (fire, pumpkin, water), glowing outlines,
 * Minecraft's post effects and the GUI are drawn afterwards, so they're never blurred.
 *
 * <p>The hook wraps the call in a condition that is always true rather than injecting a callback, so nothing is
 * allocated per frame (same pattern as {@code HudMixin}). If another mod also wraps this call and its condition says
 * no while being outermost, the blur skips that frame (and restarts its trail later); a mod that {@code @Redirect}s
 * the call would stop this hook applying (it is optional, so the game still starts; the blur then never runs). No mod in the tested stack (Sodium 0.9.2 and its
 * bundled Fabric rendering modules, ImmediatelyFast, Dynamic FPS, Lithium, EntityCulling, FerriteCore, Fabric API)
 * references {@code render3dHud}.
 */
@Mixin(GameRenderer.class)
abstract class MotionBlurMixin {
	// require = 0: if another mod takes over this call, Minecraft still starts and the blur simply never runs.
	@WrapWithCondition(method = "renderLevel", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
			+ "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private boolean flint$motionBlur(GameRenderer self, CameraRenderState camera, PlayerRenderState player, OptionsRenderState options,
		boolean consistentDepth) {
		MotionBlur.beforeHand(self.mainRenderTarget(), camera.isPanoramicMode);
		return true;
	}
}
