package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nametags: their size, and how each text part is drawn (shadow, opacity, background). */
@Mixin(SubmitNodeCollection.class)
abstract class SubmitNodeCollectionMixin {
	@WrapOperation(method = "submitNameTag", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
	private void flint$size(PoseStack pose, float x, float y, float z, Operation<Void> original) {
		float k = Mc263Nametags.size();
		original.call(pose, x * k, y * k, z * k);
	}

	@WrapOperation(method = "submitNameTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;nameTag(Lorg/joml/Matrix4f;FFLnet/minecraft/util/FormattedCharSequence;IIILnet/minecraft/client/gui/Font$DisplayMode;)Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;"))
	private TextFeatureRenderer.Submit flint$part(Matrix4f pose, float x, float y, FormattedCharSequence name, int light, int color, int background,
		Font.DisplayMode mode, Operation<TextFeatureRenderer.Submit> original) {
		return Mc263Nametags.part(pose, x, y, name, light, color, background, mode,
			(p, px, py, n, l, c, b, m) -> original.call(p, px, py, n, l, c, b, m));
	}
}
