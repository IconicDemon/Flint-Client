package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.flintclient.client.control.platform.Mc263MoreMods;
import com.flintclient.client.control.platform.Mc263ViewModel;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * View Model on Minecraft 1.21.1: the held item is moved where Minecraft starts placing a hand (in view space, before its
 * swing and bob), and rotated and scaled about itself just before it is drawn. The empty hand is left alone. Nothing is
 * allocated. 1.21.1 draws the held item through ItemInHandRenderer.renderItem (ItemRenderer.renderStatic), so the hand
 * being drawn is remembered from renderArmWithItem. Totem's held size and Shield Status's tint are applied there too.
 */
@Mixin(ItemInHandRenderer.class)
abstract class FirstPersonItemMixin {
	@Unique
	private static InteractionHand flint$hand = InteractionHand.MAIN_HAND;

	@Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", ordinal = 0, target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V",
		shift = At.Shift.AFTER))
	private void flint$place(AbstractClientPlayer player, float a, float b, InteractionHand hand, float c, ItemStack stack, float d, PoseStack pose,
		MultiBufferSource buffers, int light, CallbackInfo callback) {
		flint$hand = hand;
		Mc263ViewModel.place(pose, hand, stack);
	}

	@WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V"))
	private void flint$shape(ItemRenderer renderer, LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean left, PoseStack pose,
		MultiBufferSource buffers, Level level, int light, int overlay, int seed, Operation<Void> original) {
		if (!context.firstPerson()) {
			original.call(renderer, entity, stack, context, left, pose, buffers, level, light, overlay, seed);
			return;
		}
		InteractionHand hand = flint$hand;
		// Totem: a held totem at its own size. Shield Status: a held shield in its tint.
		float totem = Mc263MoreMods.handScale(stack);
		boolean shapes = Mc263ViewModel.shapes();
		int tint = Mc263LookMods.beginHeld(stack);
		if (!shapes && totem == 1) {
			try {
				original.call(renderer, entity, stack, context, left, pose, buffers, level, light, overlay, seed);
			} finally {
				Mc263LookMods.endHeld(tint);
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
			original.call(renderer, entity, stack, context, left, pose, buffers, level, light, overlay, seed);
		} finally {
			pose.popPose();
			Mc263LookMods.endHeld(tint);
		}
	}
}
