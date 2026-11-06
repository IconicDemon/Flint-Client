package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Hitbox;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hitbox (combat-hitbox's look) on Minecraft 1.21.1, which has no hitbox gizmos: each entity F3+B outlines (Minecraft
 * already checked it's on and visible) is handed to Flint, which draws it with the frame's gizmos; Minecraft's own box
 * (and its parts' boxes, drawn inside it) is left out.
 */
@Mixin(EntityRenderDispatcher.class)
abstract class HitboxRenderMixin {
	@WrapWithCondition(method = "render", at = @At(value = "INVOKE", ordinal = 0,
		target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;FFFF)V"))
	private boolean flint$hitbox(PoseStack pose, VertexConsumer lines, Entity entity, float partialTicks, float r, float g, float b) {
		// A menu picture (the inventory) keeps Minecraft's box in the picture: Flint's are drawn in the world.
		return com.flintclient.client.control.platform.Mc263Skins.inGuiPicture() || !Mc263Hitbox.queue(entity, partialTicks);
	}
}
