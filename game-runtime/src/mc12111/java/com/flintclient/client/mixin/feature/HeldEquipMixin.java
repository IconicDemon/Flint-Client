package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263ViewModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Customizer's Cancel Re-Equip on Minecraft 1.21.11 and 1.21.10 (ItemInHandRenderer keeps the hands' heights): the held items never dip out of view (switching, an item that changes, the
 * attack cooldown, using an item); the new item shows at once. Hands busy (rowing a boat) still lower as Minecraft lowers them.
 */
@Mixin(ItemInHandRenderer.class)
abstract class HeldEquipMixin {
	@Shadow
	private ItemStack mainHandItem;
	@Shadow
	private ItemStack offHandItem;
	@Shadow
	private float mainHandHeight;
	@Shadow
	private float oMainHandHeight;
	@Shadow
	private float offHandHeight;
	@Shadow
	private float oOffHandHeight;

	@Inject(method = "tick", at = @At("TAIL"))
	private void flint$noReEquip(CallbackInfo callback) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		if (Mc263ViewModel.cancelReEquip() && !player.isHandsBusy()) {
			mainHandItem = player.getMainHandItem();
			offHandItem = player.getOffhandItem();
			mainHandHeight = oMainHandHeight = 1;
			offHandHeight = oOffHandHeight = 1;
		}
	}

	@Inject(method = "itemUsed", at = @At("TAIL"))
	private void flint$noDipOnUse(InteractionHand hand, CallbackInfo callback) {
		if (Mc263ViewModel.cancelReEquip()) {
			mainHandHeight = oMainHandHeight = 1;
			offHandHeight = oOffHandHeight = 1;
		}
	}
}
