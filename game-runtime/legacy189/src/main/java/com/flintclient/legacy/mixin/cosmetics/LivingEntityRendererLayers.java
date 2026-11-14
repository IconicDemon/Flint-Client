package com.flintclient.legacy.mixin.cosmetics;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.layer.EntityRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A layer added to a renderer (the 3D cosmetics' on the player renderer). */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererLayers {
	@Invoker("addLayer")
	boolean flint$addLayer(EntityRenderLayer<?> layer);
}
