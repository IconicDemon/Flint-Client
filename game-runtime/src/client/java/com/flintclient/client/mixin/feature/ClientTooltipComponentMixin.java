package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Containers;
import com.flintclient.client.control.platform.Mc263Food;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shulker Preview and Saturation: draw their tooltip parts (Minecraft's own switch knows only its own). */
@Mixin(ClientTooltipComponent.class)
interface ClientTooltipComponentMixin {
	@Inject(method = "create(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
		at = @At("HEAD"), cancellable = true)
	private static void flint$shulker(TooltipComponent component, CallbackInfoReturnable<ClientTooltipComponent> callback) {
		ClientTooltipComponent mine = Mc263Containers.clientTooltip(component);
		if (mine != null) {
			callback.setReturnValue(mine);
		}
	}

	/** Saturation: the food icons line of a tooltip (Mc263Food.tooltipLines). */
	@Inject(method = "create(Lnet/minecraft/util/FormattedCharSequence;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
		at = @At("HEAD"), cancellable = true)
	private static void flint$food(FormattedCharSequence line, CallbackInfoReturnable<ClientTooltipComponent> callback) {
		ClientTooltipComponent mine = Mc263Food.clientTooltip(line);
		if (mine != null) {
			callback.setReturnValue(mine);
		}
	}
}
