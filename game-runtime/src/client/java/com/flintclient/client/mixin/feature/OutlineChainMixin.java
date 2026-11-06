package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Set;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mob Overlay's outline thickness: Minecraft's outline effect blurs its edge by a fixed radius; while Mob Overlay outlines
 * mobs, the same effect with another radius (Flint's own copies of it) is used instead. If one can't load, Minecraft's is.
 */
@Mixin(LevelRenderer.class)
abstract class OutlineChainMixin {
	@WrapOperation(method = com.flintclient.client.compat.McCompat.LEVEL_RENDER, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ShaderManager;getPostChain(Lnet/minecraft/resources/Identifier;Ljava/util/Set;)Lnet/minecraft/client/renderer/PostChain;"))
	private PostChain flint$thickness(ShaderManager shaders, Identifier id, Set<Identifier> targets, Operation<PostChain> original) {
		Identifier wanted = Mc263LookMods.outlineChain(id);
		if (wanted != id) {
			PostChain chain = original.call(shaders, wanted, targets);
			if (chain != null) {
				return chain;
			}
		}
		return original.call(shaders, id, targets);
	}
}
