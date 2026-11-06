package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.state.LivingEntityRenderState;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.compat.state.SubmitNodeCollector;
import com.flintclient.client.control.platform.Mc263HealthIndicators;
import com.flintclient.client.control.platform.Mc263Nametags;
import com.flintclient.client.control.platform.MobFillLayer;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.21.1 draws a living thing straight from the game: as it starts, its render state (compat.state) is filled from
 * it, as 1.21.2's extractRenderState would, and Nametags (chat bubbles) and Health Indicators read it; after its geometry
 * and nametag, the hearts. Mob Overlay: every living thing's renderer gets the fill layer. Nametags: your own nametag in
 * third person by Minecraft's own nametag rules (vanilla never shows the camera entity's; Mc263Nametags.own).
 */
@Mixin(LivingEntityRenderer.class)
abstract class LivingEntityRendererMixin {
	@Shadow
	protected abstract boolean addLayer(RenderLayer layer);

	@Shadow
	protected abstract boolean shouldShowName(LivingEntity entity);

	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "<init>", at = @At("TAIL"))
	private void flint$fillLayer(CallbackInfo callback) {
		addLayer(new MobFillLayer((LivingEntityRenderer) (Object) this));
	}

	@Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"))
	private void flint$extract(LivingEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
		CallbackInfo callback) {
		LivingEntityRenderState state = (LivingEntityRenderState) RenderStates.fill(entity, partialTick, light,
			shouldShowName(entity) ? entity.getDisplayName() : null);
		Mc263Nametags.extract(entity, state, partialTick);
		Mc263HealthIndicators.extract(entity, state);
		// The maps' mob heads: the model and texture this living thing is drawn with.
		if (com.flintclient.client.control.platform.EntityIcons.active() && !(entity instanceof net.minecraft.world.entity.player.Player)) {
			@SuppressWarnings({"unchecked", "rawtypes"})
			LivingEntityRenderer renderer = (LivingEntityRenderer) (Object) this;
			com.flintclient.client.control.platform.EntityIcons.seen(entity, renderer.getTextureLocation(entity), renderer.getModel());
		}
	}

	/** Health Indicators: a player's hearts, after its geometry and nametag. */
	@Inject(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("RETURN"))
	private void flint$hearts(LivingEntity entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
		CallbackInfo callback) {
		if (RenderStates.peek(entity) instanceof LivingEntityRenderState state) {
			Mc263HealthIndicators.submit(state, pose, new SubmitNodeCollector(buffers), RenderStates.camera());
		}
	}

	@ModifyReturnValue(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("RETURN"))
	private boolean flint$ownName(boolean show, LivingEntity entity) {
		// 1.21.1 hides a nametag from 64 blocks on (32 while sneaking), before its other rules.
		double distanceSq = net.minecraft.client.Minecraft.getInstance().getEntityRenderDispatcher().distanceToSqr(entity);
		return Mc263Nametags.living(entity, Mc263Nametags.own(entity, distanceSq >= (entity.isDiscrete() ? 1024.0 : 4096.0), show));
	}
}
