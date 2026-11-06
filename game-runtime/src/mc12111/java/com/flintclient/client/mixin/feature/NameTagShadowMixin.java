package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nametags' shadow on Minecraft 1.21.11, whose nametag parts have no shadow of their own: it's set where each part's text is drawn. */
@Mixin(NameTagFeatureRenderer.class)
abstract class NameTagShadowMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"))
	private void flint$shadow(Font font, Component text, float x, float y, int color, boolean shadow, Matrix4f pose, MultiBufferSource buffers,
		Font.DisplayMode mode, int background, int light, Operation<Void> original) {
		original.call(font, text, x, y, color, shadow || Mc263Nametags.shadow(mode), pose, buffers, mode, background, light);
	}
}
