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

/** Shulker Preview: a shulker box's items as its tooltip image; Saturation: a food's values as icons at the end of its tooltip. */
@Mixin(ItemStack.class)
abstract class ItemStackTooltipMixin {
	/** Elytra and container lines after the item's other details. */
	@Inject(method = "addDetailsToTooltip", at = @At("TAIL"))
	private void flint$details(Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, @Nullable Player player,
		TooltipFlag flag, java.util.function.Consumer<Component> builder, org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263MoreMods.elytraTooltip((ItemStack) (Object) this, builder);
		Mc263Containers.lines((ItemStack) (Object) this, builder);
	}

	/** Saturation: AppleSkin puts the food icons after every line, the advanced ones (id, components) included. */
	@Inject(method = "getTooltipLines", at = @At("RETURN"))
	private void flint$food(Item.TooltipContext context, @Nullable Player player, TooltipFlag flag, CallbackInfoReturnable<List<Component>> callback) {
		Mc263Food.tooltipLines((ItemStack) (Object) this, flag, callback.getReturnValue());
	}

	@ModifyReturnValue(method = "getTooltipImage", at = @At("RETURN"))
	private Optional<TooltipComponent> flint$shulker(Optional<TooltipComponent> image) {
		return Mc263Containers.image((ItemStack) (Object) this, image);
	}
}
