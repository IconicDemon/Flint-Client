package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.util.Optional;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Shulker Preview: a shulker box's items as its tooltip image. */
@Mixin(ItemStack.class)
abstract class ItemStackTooltipMixin {
	/** Saturation: a food's hunger and saturation, after its other details. */
	@org.spongepowered.asm.mixin.injection.Inject(method = "addDetailsToTooltip", at = @At("TAIL"))
	private void flint$food(net.minecraft.world.item.Item.TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
		net.minecraft.world.entity.player.@org.jspecify.annotations.Nullable Player player, net.minecraft.world.item.TooltipFlag flag,
		java.util.function.Consumer<net.minecraft.network.chat.Component> builder, org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
		com.flintclient.client.control.platform.Mc263Food.tooltip((ItemStack) (Object) this, builder);
	}

	@ModifyReturnValue(method = "getTooltipImage", at = @At("RETURN"))
	private Optional<TooltipComponent> flint$shulker(Optional<TooltipComponent> image) {
		return Mc263Visuals.shulkerImage((ItemStack) (Object) this, image);
	}
}
