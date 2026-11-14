package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.flintclient.client.control.platform.NametagParts;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.phase.TranslucentFeatureRenderPhase;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Nametags: their size, and how each text part is drawn (shadow, opacity, background, rounded corners and border, the
 * hurt tint, chat bubbles). A shaped background goes in just before its text, in the same pass.
 */
@Mixin(SubmitNodeCollection.class)
abstract class SubmitNodeCollectionMixin {
	@Shadow
	@Final
	public TranslucentFeatureRenderPhase seeThrough;

	@Shadow
	protected abstract void submitNameTagPart(TextFeatureRenderer.Submit submit);

	// The nametag's size: each of the three scale factors, as values (a wrapped call would box them for every nametag, every frame).
	@ModifyArg(method = "submitNameTag", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"), index = 0)
	private float flint$sizeX(float x) {
		return x * Mc263Nametags.size();
	}

	@ModifyArg(method = "submitNameTag", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"), index = 1)
	private float flint$sizeY(float y) {
		return y * Mc263Nametags.size();
	}

	@ModifyArg(method = "submitNameTag", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"), index = 2)
	private float flint$sizeZ(float z) {
		return z * Mc263Nametags.size();
	}

	@WrapOperation(method = "submitNameTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollection;nameTag(Lorg/joml/Matrix4f;FFLnet/minecraft/util/FormattedCharSequence;IIILnet/minecraft/client/gui/Font$DisplayMode;)Lnet/minecraft/client/renderer/feature/TextFeatureRenderer$Submit;"))
	private TextFeatureRenderer.Submit flint$part(Matrix4f pose, float x, float y, FormattedCharSequence name, int light, int color, int background,
		Font.DisplayMode mode, Operation<TextFeatureRenderer.Submit> original) {
		return NametagParts.part(pose, x, y, name, light, color, background, mode,
			(p, px, py, n, l, c, b, m) -> original.call(p, px, py, n, l, c, b, m),
			mode == Font.DisplayMode.SEE_THROUGH ? seeThrough::submit : this::submitNameTagPart);
	}
}
