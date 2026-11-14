package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.gui.DarkGui;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Dark Mode on Minecraft 1.19.2: which texture the next GUI blit draws from (1.19.2 binds it by name before blitting, where
 * 1.20 and later name it in the blit), for compat.gui.DarkGui. Both of RenderSystem's render-thread binds are followed; a
 * texture bound by raw id is not known by name. The world binds textures all the time, so the unit is passed through
 * unchanged (no callback info is allocated).
 */
@Mixin(RenderSystem.class)
abstract class DarkTextureMixin {
	@ModifyVariable(method = "_setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static int flint$named(int unit, @Local(argsOnly = true) ResourceLocation texture) {
		DarkGui.bound(unit, texture);
		return unit;
	}

	@ModifyVariable(method = "_setShaderTexture(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private static int flint$raw(int unit) {
		DarkGui.bound(unit, null);
		return unit;
	}
}
