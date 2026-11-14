package com.flintclient.legacy.mixin;

import com.flintclient.legacy.Flint;
import com.flintclient.legacy.mods.ItemCustomizer;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.ItemInHandRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.resource.model.ModelTransformations;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Customizer on 1.8.9: the held item (or the empty hand) placed where its matrix starts, turned and sized just before it is
 * drawn; food and drinks without the turn; and no re-equip dip (switching, an item that changes, using one). Each hook asks the
 * module and fails on its own (a fault turns the module off, as Flint's mods do on 1.8.9).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	@Shadow
	private ItemStack itemInHand;
	@Shadow
	private float handHeight;
	@Shadow
	private float lastHandHeight;
	@Shadow
	private int selectedSlot;

	@Inject(method = "renderInFirstPerson", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/platform/GlStateManager;pushMatrix()V",
		ordinal = 0, shift = At.Shift.AFTER))
	private void flint$place(float tickDelta, CallbackInfo ci) {
		if (!Flint.ready()) {
			return;
		}
		ItemCustomizer customizer = Flint.modules().itemCustomizer;
		try {
			customizer.place(itemInHand);
		} catch (RuntimeException | LinkageError e) {
			Flint.fault(customizer, e);
		}
	}

	@WrapOperation(method = "renderInFirstPerson", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/render/ItemInHandRenderer;render(Lnet/minecraft/entity/living/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/resource/model/ModelTransformations$Type;)V"))
	private void flint$shape(ItemInHandRenderer renderer, LivingEntity entity, ItemStack stack, ModelTransformations.Type type,
		Operation<Void> original) {
		ItemCustomizer customizer = Flint.ready() ? Flint.modules().itemCustomizer : null;
		boolean shapes = false;
		try {
			shapes = customizer != null && customizer.shapes();
		} catch (RuntimeException | LinkageError e) {
			Flint.fault(customizer, e);
		}
		if (!shapes) {
			original.call(renderer, entity, stack, type);
			return;
		}
		GlStateManager.pushMatrix();
		try {
			customizer.shape();
			original.call(renderer, entity, stack, type);
		} finally {
			GlStateManager.popMatrix();
		}
	}

	@WrapWithCondition(method = "applyConsuming", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/platform/GlStateManager;rotatef(FFFF)V"))
	private boolean flint$eatTurn(float angle, float x, float y, float z) {
		return !Flint.ready() || Flint.modules().itemCustomizer.eatRotation();
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$noReEquip(CallbackInfo ci) {
		if (!Flint.ready() || !Flint.modules().itemCustomizer.cancelReEquip()) {
			return;
		}
		PlayerEntity player = Minecraft.getInstance().player;
		if (player != null) {
			itemInHand = player.inventory.getSelectedItem();
			selectedSlot = player.inventory.selectedSlot;
			handHeight = 1.0F;
			lastHandHeight = 1.0F;
		}
	}

	@Inject(method = {"onItemUsed", "onBlockUsed"}, at = @At("TAIL"))
	private void flint$noDipOnUse(CallbackInfo ci) {
		if (Flint.ready() && Flint.modules().itemCustomizer.cancelReEquip()) {
			handHeight = 1.0F;
			lastHandHeight = 1.0F;
		}
	}
}
