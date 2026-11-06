package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Containers;
import com.flintclient.client.control.platform.Mc263Food;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shulker Preview: a shulker box's items as its tooltip image; Elytras' and the containers' lines; Saturation: a food's values
 * as icons at the end of its tooltip. Minecraft 1.21.1 builds a tooltip's lines in getTooltipLines alone (the item's details
 * have no method of their own), so the extra lines go at its end, before the food icons.
 */
@Mixin(ItemStack.class)
abstract class ItemStackTooltipMixin {
	@Inject(method = "getTooltipLines", at = @At("RETURN"))
	private void flint$lines(Item.TooltipContext context, @Nullable Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> callback) {
		List<Component> lines = callback.getReturnValue();
		ItemStack stack = (ItemStack) (Object) this;
		if (lines != null) {
			com.flintclient.client.control.platform.Mc263MoreMods.elytraTooltip(stack, lines::add);
			Mc263Containers.lines(stack, lines::add);
		}
		Mc263Food.tooltipLines(stack, flag, lines);
	}

	@ModifyReturnValue(method = "getTooltipImage", at = @At("RETURN"))
	private Optional<TooltipComponent> flint$shulker(Optional<TooltipComponent> image) {
		return Mc263Containers.image((ItemStack) (Object) this, image);
	}
}
