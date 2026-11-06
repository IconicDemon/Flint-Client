package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.flintclient.client.control.platform.MobFillLayer;
import com.flintclient.client.control.platform.MobFillState;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nametags: your own nametag in third person by Minecraft's own nametag rules (vanilla never shows the camera entity's;
 * Mc263Nametags.own), the Nametags mod's hide keys, and each player's chat bubbles for the frame. Mob Overlay: every living thing's
 * renderer gets the fill layer, and each state is told whether (and in what colour) to fill.
 */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
	@Shadow
	protected abstract boolean addLayer(RenderLayer layer);

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "<init>", at = @At("TAIL"))
	private void flint$fillLayer(CallbackInfo callback) {
		addLayer(new MobFillLayer((LivingEntityRenderer) (Object) this));
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
		at = @At("TAIL"))
	private void flint$fill(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo callback) {
		((com.flintclient.client.control.platform.EntityRef) state).flint$entity(entity);
		((MobFillState) state).flint$fill(Mc263LookMods.fill(entity));
		com.flintclient.client.control.platform.Mc263Nametags.extract(entity, state, partialTick);
		com.flintclient.client.control.platform.Mc263HealthIndicators.extract(entity, state);
	}

	/** The maps' mob heads: the model and texture this living thing is drawn with (its renderer has just picked the model). */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"))
	private void flint$mapHead(LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
		net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo callback) {
		if (com.flintclient.client.control.platform.EntityIcons.active()
			&& ((com.flintclient.client.control.platform.EntityRef) state).flint$entity() instanceof LivingEntity entity
			&& !(entity instanceof net.minecraft.world.entity.player.Player)) {
			LivingEntityRenderer renderer = (LivingEntityRenderer) (Object) this;
			com.flintclient.client.control.platform.EntityIcons.seen(entity, renderer.getTextureLocation(state), renderer.getModel());
		}
	}

	/** Health Indicators: a player's hearts, after its geometry. */
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;"
		+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("RETURN"))
	private void flint$hearts(LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
		net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263HealthIndicators.submit(state, pose, collector, camera);
	}

	@ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("RETURN"))
	private boolean flint$ownName(boolean show, LivingEntity entity, double distanceToCameraSq) {
		// Minecraft hides a sneaking entity's nametag from 32 blocks on, before its other rules.
		return com.flintclient.client.control.platform.Mc263Nametags.living(entity,
			com.flintclient.client.control.platform.Mc263Nametags.own(entity, entity.isDiscrete() && distanceToCameraSq >= 1024.0, show));
	}
}
