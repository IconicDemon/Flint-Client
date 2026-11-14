package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Shields;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Your shield in first person is your Flint shield too. */
@Mixin(ItemInHandRenderer.class)
abstract class FirstPersonShieldCosmeticsMixin {
	@WrapMethod(method = "renderArmWithItem")
	private void flint$ownShield(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swing, ItemStack stack,
		float equip, PoseStack pose, MultiBufferSource buffers, int light, Operation<Void> original) {
		Mc263Shields.beginSelf();
		try {
			original.call(player, partialTick, pitch, hand, swing, stack, equip, pose, buffers, light);
		} finally {
			Mc263Shields.end();
		}
	}
}
