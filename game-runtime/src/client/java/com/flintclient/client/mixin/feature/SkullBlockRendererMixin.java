package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Skins;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 3D Skins on player heads (placed, worn and held): every player head's look comes from one skin render type, so each is
 * remembered with its skin, and a player head is drawn with a head model whose hat layer is that skin's voxels.
 */
@Mixin(SkullBlockRenderer.class)
abstract class SkullBlockRendererMixin {
	@Inject(method = "getPlayerSkinRenderType", at = @At("RETURN"))
	private static void flint$skin(Identifier skin, CallbackInfoReturnable<RenderType> callback) {
		Mc263Skins.headSkin(callback.getReturnValue(), skin);
	}

	@Inject(method = "submitSkull", at = @At("HEAD"), cancellable = true)
	private static void flint$voxelHead(float animation, PoseStack pose, SubmitNodeCollector collector, int light, SkullModelBase model,
		RenderType type, int outline, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling, CallbackInfo callback) {
		SkullModelBase head = Mc263Skins.head(model, type, pose);
		if (head != null) {
			callback.cancel();
			SkullBlockRenderer.submitSkull(animation, pose, collector, light, head, type, outline, crumbling);
		}
	}
}
