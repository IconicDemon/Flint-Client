package com.flintclient.client.mixin.fx;

import com.flintclient.client.control.fx.MotionBlur;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Motion Blur's early pass ({@link MotionBlur#early}) between the solid terrain and the solid features (players, mobs, block
 * entities), as Natural Motion Blur's MixinLevelRenderer splits it on 26.3. Minecraft draws the terrain, the solid features and
 * (without Improved Transparency) the transparent layers in one render pass, and nothing can be copied or blurred while a pass is
 * open. So, only on frames that want the early pass: the terrain's pass is ended after the terrain, the terrain is blurred, the
 * solid features are drawn in a pass of their own, and {@code executeClassicTransparency}, handed the ended pass, is given a
 * fresh one for the transparent layers (closed again as it returns; Minecraft's own close of the ended pass then does nothing).
 * Every other frame runs exactly as Minecraft wrote it.
 *
 * <p>Allocation-free hooks (a condition and two variable swaps, no callbacks). If another mod takes these calls over, the hooks
 * don't apply and the late pass blurs the whole frame instead ({@link com.flintclient.client.control.fx.NmbTiming#lateMode}).
 */
@Mixin(LevelRenderer.class)
abstract class MotionBlurLevelMixin {
	@Shadow
	@Final
	private LevelTargetBundle targets;
	/** The terrain's pass this frame's split ended, until the transparent layers are handed it. */
	@Unique
	private @Nullable RenderPass flint$endedPass;
	/** The pass opened for the transparent layers, closed when they're done. */
	@Unique
	private @Nullable RenderPass flint$transparencyPass;

	@WrapWithCondition(method = "executeSolid", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;executeSolid(Lcom/mojang/renderpearl/api/commands/RenderPass;)V"))
	private boolean flint$motionBlurEarly(PreparedFrame frame, RenderPass terrainPass) {
		flint$endedPass = null;
		if (!MotionBlur.wantsEarly() || targets.main == null) {
			return true;
		}
		RenderTarget main = targets.main.get();
		if (main.getColorTextureView() == null) {
			return true;
		}
		terrainPass.close();
		flint$endedPass = terrainPass;
		MotionBlur.early(main);
		try (RenderPass features = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Flint motion blur / solid features",
			main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(features);
			frame.executeSolid(features);
		}
		return false;
	}

	@ModifyVariable(method = "executeClassicTransparency", require = 0, at = @At("HEAD"), argsOnly = true)
	private RenderPass flint$motionBlurReopen(RenderPass pass) {
		if (pass == null || pass != flint$endedPass) {
			return pass;
		}
		flint$endedPass = null;
		RenderTarget main = targets.main.get();
		RenderPass reopened = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Flint motion blur / transparency",
			main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty());
		RenderSystem.bindDefaultUniforms(reopened);
		flint$transparencyPass = reopened;
		return reopened;
	}

	@ModifyVariable(method = "executeClassicTransparency", require = 0, at = @At("RETURN"), argsOnly = true)
	private RenderPass flint$motionBlurReclose(RenderPass pass) {
		if (pass != null && pass == flint$transparencyPass) {
			flint$transparencyPass = null;
			pass.close();
		}
		return pass;
	}
}
