package com.flintclient.client.control.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;

/**
 * Mob Overlay's Full mode on Minecraft 1.21.1: the mob's model drawn once more over itself in a flat, translucent colour (lit
 * evenly, so it reads at night too). Added to every living thing's renderer (mixin.feature.LivingEntityRendererMixin); draws
 * only when the entity has a fill this frame.
 */
public final class MobFillLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
	public MobFillLayer(RenderLayerParent<T, M> parent) {
		super(parent);
	}

	@Override
	public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity, float limbSwing, float limbSwingAmount, float partialTick,
		float ageInTicks, float yaw, float pitch) {
		int fill = Mc263LookMods.fill(entity);
		if (fill == 0) {
			return;
		}
		getParentModel().renderToBuffer(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(Mc263LookMods.white())), light,
			OverlayTexture.NO_OVERLAY, fill);
	}
}
