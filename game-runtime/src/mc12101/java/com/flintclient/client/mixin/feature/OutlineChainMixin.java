package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mob Overlay's outline thickness on Minecraft 1.21.1: Minecraft's outline effect blurs its edge by a fixed radius; while Mob
 * Overlay outlines mobs, the same effect with another radius (Flint's copies, shaders/post/entity_outline_rN.json, whose blur
 * program reads its radius) is made instead when the level renderer sets the outline up, and set up again once the
 * thickness changes. If Flint's can't load, Minecraft's is.
 */
@Mixin(LevelRenderer.class)
abstract class OutlineChainMixin {
	@Unique
	private static final ResourceLocation FLINT$VANILLA = ResourceLocation.withDefaultNamespace("entity_outline");

	/** The chain Flint last set up (Minecraft's own id when none of Flint's). */
	@Unique
	private @Nullable ResourceLocation flint$chain;

	@Shadow
	public abstract void initOutline();

	@Shadow
	private @Nullable PostChain entityEffect;

	@WrapOperation(method = "initOutline", at = @At(value = "NEW", target = "net/minecraft/client/renderer/PostChain"))
	private PostChain flint$thickness(TextureManager textures, ResourceProvider resources, RenderTarget main, ResourceLocation id,
		Operation<PostChain> original) {
		ResourceLocation wanted = Mc263LookMods.outlineChain(FLINT$VANILLA);
		flint$chain = wanted;
		if (!wanted.equals(FLINT$VANILLA)) {
			try {
				return original.call(textures, resources, main,
					ResourceLocation.fromNamespaceAndPath(wanted.getNamespace(), "shaders/post/" + wanted.getPath() + ".json"));
			} catch (Exception e) {
				// Flint's chain didn't load (a resource pack replaced the program, a driver refused it): Minecraft's own.
				com.flintclient.client.FlintClient.LOGGER.warn("Flint: the outline thickness {} didn't load; Minecraft's outline is used", wanted, e);
			}
		}
		return original.call(textures, resources, main, id);
	}

	/** Each client tick: a thickness change sets the outline up again with its chain. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$thicknessChanged(CallbackInfo callback) {
		if (flint$chain != null && entityEffect != null && !Mc263LookMods.outlineChain(FLINT$VANILLA).equals(flint$chain)) {
			initOutline();
		}
	}
}
