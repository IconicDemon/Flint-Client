package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ChatHeads;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chat Heads: the player-head glyph's face and hat quads, moved and spread by 3Dness (and cut round with Round heads)
 * while chat and the GUI's text are drawn. (1.21.x: Matrix4f, where 26.x has Matrix4fc.)
 */
@Mixin(targets = "net.minecraft.client.gui.font.PlayerGlyphProvider$Instance")
abstract class ChatHeadsQuadMixin {
	@WrapOperation(method = "renderSprite", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/font/PlayerGlyphProvider$Instance;renderQuad(Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/VertexConsumer;IFFFFFIFFIIII)V"))
	private void flint$quad(Matrix4f pose, VertexConsumer buffer, int light, float x0, float x1, float y0, float y1, float z, int color,
		float u, float v, int uw, int vh, int textureWidth, int textureHeight, Operation<Void> original) {
		Mc263ChatHeads.headQuad(x0, x1, y0, y1, u, v, uw, vh, (qx0, qx1, qy0, qy1, qu, qv, quw, qvh) -> original.call(pose, buffer, light, qx0, qx1, qy0,
			qy1, z, color, qu, qv, quw, qvh, textureWidth, textureHeight));
	}
}
