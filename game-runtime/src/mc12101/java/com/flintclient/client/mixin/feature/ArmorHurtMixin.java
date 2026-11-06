package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.state.HumanoidRenderState;
import com.flintclient.client.compat.state.RenderStates;
import com.flintclient.client.control.platform.Mc263Tools;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animations' Armor hurt color on Minecraft 1.21.1: while a wearer's armor is drawn, the armor (and its trims) may take the
 * wearer's red flash instead of none. 1.21.1 draws armor in HumanoidArmorLayer itself (later versions in
 * EquipmentLayerRenderer); the glint keeps no overlay.
 */
@Mixin(HumanoidArmorLayer.class)
abstract class ArmorHurtMixin {
	private static final String RENDER = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V";

	@Inject(method = RENDER, at = @At("HEAD"))
	private void flint$begin(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity, float a, float b, float c, float d, float e,
		float f, CallbackInfo callback) {
		if (RenderStates.of(entity) instanceof HumanoidRenderState state) {
			state.hasRedOverlay = entity.hurtTime > 0 || entity.deathTime > 0;
			Mc263Tools.beginArmor(state);
		}
	}

	@Inject(method = RENDER, at = @At("RETURN"))
	private void flint$end(PoseStack pose, MultiBufferSource buffers, int light, LivingEntity entity, float a, float b, float c, float d, float e,
		float f, CallbackInfo callback) {
		Mc263Tools.endArmor();
	}

	@ModifyExpressionValue(method = {"renderModel", "renderTrim"},
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/texture/OverlayTexture;NO_OVERLAY:I"))
	private int flint$overlay(int none) {
		return Mc263Tools.armorOverlay(none);
	}
}
