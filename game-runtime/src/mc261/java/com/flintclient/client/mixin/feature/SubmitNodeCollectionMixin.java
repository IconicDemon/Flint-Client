package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Nametags: their size, and how each text part is drawn (opacity, background, hurt tint, chat bubbles; the shadow is
 * NameTagShadowMixin's). Minecraft 26.1's {@code SubmitNodeCollection.submitNameTag} hands the tag to
 * {@code NameTagFeatureRenderer.Storage.add}, which scales it and makes its parts: the see-through-walls part goes in
 * the see-through list, the others in the normal one ({@code NameTagFeatureRenderer} draws them in those modes).
 */
@Mixin(NameTagFeatureRenderer.Storage.class)
abstract class SubmitNodeCollectionMixin {
	@WrapOperation(method = "add", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V"))
	private void flint$size(PoseStack pose, float x, float y, float z, Operation<Void> original) {
		float k = Mc263Nametags.size();
		original.call(pose, x * k, y * k, z * k);
	}

	/** A visible-through-walls tag's solid text (the normal list, no background). */
	@WrapOperation(method = "add", at = @At(value = "NEW", ordinal = 0,
		target = "net/minecraft/client/renderer/SubmitNodeStorage$NameTagSubmit"))
	private SubmitNodeStorage.NameTagSubmit flint$text(Matrix4fc pose, float x, float y, Component name, int light, int color, int background,
		double distance, Operation<SubmitNodeStorage.NameTagSubmit> original) {
		return flint$part(pose, x, y, name, light, color, background, distance, Font.DisplayMode.NORMAL, original);
	}

	/** Its see-through part (faint text on the background, drawn through walls). */
	@WrapOperation(method = "add", at = @At(value = "NEW", ordinal = 1,
		target = "net/minecraft/client/renderer/SubmitNodeStorage$NameTagSubmit"))
	private SubmitNodeStorage.NameTagSubmit flint$seeThrough(Matrix4fc pose, float x, float y, Component name, int light, int color, int background,
		double distance, Operation<SubmitNodeStorage.NameTagSubmit> original) {
		return flint$part(pose, x, y, name, light, color, background, distance, Font.DisplayMode.SEE_THROUGH, original);
	}

	/** A sneaking entity's tag: one faint part, not through walls. */
	@WrapOperation(method = "add", at = @At(value = "NEW", ordinal = 2,
		target = "net/minecraft/client/renderer/SubmitNodeStorage$NameTagSubmit"))
	private SubmitNodeStorage.NameTagSubmit flint$sneaking(Matrix4fc pose, float x, float y, Component name, int light, int color, int background,
		double distance, Operation<SubmitNodeStorage.NameTagSubmit> original) {
		return flint$part(pose, x, y, name, light, color, background, distance, Font.DisplayMode.NORMAL, original);
	}

	private static SubmitNodeStorage.NameTagSubmit flint$part(Matrix4fc pose, float x, float y, Component name, int light, int color, int background,
		double distance, Font.DisplayMode mode, Operation<SubmitNodeStorage.NameTagSubmit> original) {
		Mc263Nametags.Look look = Mc263Nametags.look(color, background, mode);
		if (look == null) {
			return original.call(pose, x, y, name, light, color, background, distance);
		}
		if (look.skip) {
			return original.call(pose, x, y, Component.empty(), light, 0, 0, distance);
		}
		// 26.1 has no standalone backgrounds: a shaped one is drawn as Minecraft's plain background (its rows say so).
		return original.call(pose, x, y, name, light, look.text, look.background != 0 ? look.background : look.fill, distance);
	}
}
