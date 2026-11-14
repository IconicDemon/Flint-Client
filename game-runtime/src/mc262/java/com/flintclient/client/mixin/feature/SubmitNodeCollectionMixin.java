package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Nametags: their size, and how each text part is drawn (opacity, background, hurt tint, chat bubbles; the shadow is NameTagShadowMixin's). */
@Mixin(SubmitNodeCollection.class)
abstract class SubmitNodeCollectionMixin {
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

	/** Minecraft 26.2 makes each part as a NameTagFeatureRenderer.Submit right in submitNameTag. */
	@WrapOperation(method = "submitNameTag", at = @At(value = "NEW", target = "net/minecraft/client/renderer/feature/NameTagFeatureRenderer$Submit"))
	private NameTagFeatureRenderer.Submit flint$part(Matrix4fc pose, float x, float y, Component name, int light, int color, int background,
		Font.DisplayMode mode, Operation<NameTagFeatureRenderer.Submit> original) {
		Mc263Nametags.Look look = Mc263Nametags.look(color, background, mode);
		if (look == null) {
			return original.call(pose, x, y, name, light, color, background, mode);
		}
		if (look.skip) {
			return original.call(pose, x, y, Component.empty(), light, 0, 0, mode);
		}
		// 26.2 has no standalone backgrounds: a shaped one is drawn as Minecraft's plain background (its rows say so).
		return original.call(pose, x, y, name, light, look.text, look.background != 0 ? look.background : look.fill, mode);
	}
}
