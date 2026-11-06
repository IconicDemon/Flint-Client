package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Nametags;
import com.flintclient.client.control.platform.Mc263Overlay;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.TntRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nametags as Flint shows them: Nick Hider's names, the Nametags mod's additions, Totem Tracker's count, the Flint mark;
 * whose nametag is being drawn (for the hurt tint and player-only borders), players' chat bubbles above it, and lit TNT's timer.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
	@ModifyReturnValue(method = "getNameTag", at = @At("RETURN"))
	private @Nullable Component flint$nameTag(@Nullable Component name, Entity entity) {
		return name == null ? null : Mc263Overlay.pingTag(entity, Mc263Nametags.badge(entity,
			com.flintclient.client.control.platform.Mc263MoreMods.totemTag(entity, Mc263Nametags.text(entity, Mc263Overlay.nameTag(entity, name)))));
	}

	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V", at = @At("HEAD"))
	private void flint$nameFor(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, int yOffset,
		CallbackInfo callback) {
		Mc263Nametags.drawing(state);
	}

	@Inject(method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V", at = @At("RETURN"))
	private void flint$nameDone(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, int yOffset,
		CallbackInfo callback) {
		Mc263Nametags.drawing(null);
	}

	@Inject(method = "submit", at = @At("TAIL"))
	private void flint$bubbles(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo callback) {
		Mc263Nametags.bubbles(state, pose, collector, camera);
		// TNT's renderer ends here too (it draws the block, then calls this): its timer, above it in the world.
		if (state instanceof TntRenderState tnt) {
			Mc263Overlay.tntLabel(tnt.fuseRemainingInTicks - 1.0F, tnt.boundingBoxHeight, tnt.distanceToCameraSq, pose, collector, camera);
		}
	}
}
