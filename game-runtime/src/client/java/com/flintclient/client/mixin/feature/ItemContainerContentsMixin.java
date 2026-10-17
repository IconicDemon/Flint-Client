package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shulker Preview: leaves out the text list of a container's items while the grid shows them. */
@Mixin(ItemContainerContents.class)
abstract class ItemContainerContentsMixin {
	@Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
	private void flint$hideList(Item.TooltipContext context, Consumer<Component> consumer, TooltipFlag flag, DataComponentGetter components,
		CallbackInfo callback) {
		if (Mc263Visuals.hideContainerList()) {
			callback.cancel();
		}
	}
}
