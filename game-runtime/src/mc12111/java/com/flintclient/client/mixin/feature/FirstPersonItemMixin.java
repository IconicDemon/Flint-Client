package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ViewModel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * View Model: the held item is moved where Minecraft starts placing a hand (in view space, before its swing and bob), and
 * rotated and scaled about itself just before it is drawn. The empty hand is left alone. Nothing is allocated. Minecraft
 * Minecraft 1.21.11 (as 26.1) draws the held item through ItemInHandRenderer.renderItem (shared with other first-person items), so the hand
 * being drawn is remembered from renderArmWithItem.
 */
@Mixin(ItemInHandRenderer.class)
abstract class FirstPersonItemMixin {
	@Unique
	private static InteractionHand flint$hand = InteractionHand.MAIN_HAND;

	@Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
		shift = At.Shift.AFTER))
	private void flint$place(AbstractClientPlayer player, float a, float b, InteractionHand hand, float c, ItemStack stack, float d, PoseStack pose,
		SubmitNodeCollector collector, int light, CallbackInfo callback) {
		flint$hand = hand;
		Mc263ViewModel.place(pose, hand, stack);
	}

	@WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void flint$shape(ItemStackRenderState item, PoseStack pose, SubmitNodeCollector collector, int a, int b, int c, Operation<Void> original,
		@Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true) ItemDisplayContext context) {
		if (!context.firstPerson()) {
			original.call(item, pose, collector, a, b, c);
			return;
		}
		InteractionHand hand = flint$hand;
		// Totem: a held totem at its own size. Shield Status: a held shield in its tint.
		float totem = com.flintclient.client.control.platform.Mc263MoreMods.handScale(stack);
		boolean shapes = Mc263ViewModel.shapes();
		int tint = com.flintclient.client.control.platform.Mc263LookMods.beginHeld(stack);
		if (!shapes && totem == 1) {
			try {
				original.call(item, pose, collector, a, b, c);
			} finally {
				com.flintclient.client.control.platform.Mc263LookMods.endHeld(tint);
			}
			return;
		}
		pose.pushPose();
		try {
			if (shapes) {
				Mc263ViewModel.shape(pose, hand);
			}
			if (totem != 1) {
				pose.scale(Math.max(0.01F, totem), Math.max(0.01F, totem), Math.max(0.01F, totem));
			}
			original.call(item, pose, collector, a, b, c);
		} finally {
			pose.popPose();
			com.flintclient.client.control.platform.Mc263LookMods.endHeld(tint);
		}
	}
}
