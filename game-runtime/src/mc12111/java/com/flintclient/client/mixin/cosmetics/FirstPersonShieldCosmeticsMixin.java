package com.flintclient.client.mixin.cosmetics;

import com.flintclient.client.cosmetics.platform.Mc263Shields;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Your shield in first person is your Flint shield too (1.21.x draws the first-person hands in ItemInHandRenderer). */
@Mixin(ItemInHandRenderer.class)
abstract class FirstPersonShieldCosmeticsMixin {
	@WrapMethod(method = "renderArmWithItem")
	private void flint$ownShield(AbstractClientPlayer player, float a, float b, InteractionHand hand, float c, ItemStack stack, float d,
		PoseStack pose, SubmitNodeCollector collector, int light, Operation<Void> original) {
		Mc263Shields.beginSelf();
		try {
			original.call(player, a, b, hand, c, stack, d, pose, collector, light);
		} finally {
			Mc263Shields.end();
		}
	}
}
