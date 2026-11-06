package com.flintclient.client.control.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Mob Overlay's Full mode: the mob's model drawn once more over itself in a flat, translucent colour (lit evenly, so it
 * reads at night too). Added to every living thing's renderer; draws only when the state carries a fill.
 */
public final class MobFillLayer<S extends LivingEntityRenderState, M extends EntityModel<? super S>> extends RenderLayer<S, M> {
	public MobFillLayer(RenderLayerParent<S, M> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, S state, float yRot, float xRot) {
		int fill = ((MobFillState) state).flint$fill();
		if (fill == 0) {
			return;
		}
		// The short overload takes its last number as the outline colour; this takes the tint (and no outline).
		com.flintclient.client.compat.McCompat.submitTinted(collector, getParentModel(), state, pose,
			RenderTypes.entityTranslucentEmissive(Mc263LookMods.white()), light, OverlayTexture.NO_OVERLAY, fill);
	}
}
