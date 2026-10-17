package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shulker Preview: draws its tooltip image (Minecraft's own switch knows only its own images). */
@Mixin(ClientTooltipComponent.class)
interface ClientTooltipComponentMixin {
	@Inject(method = "create(Lnet/minecraft/world/inventory/tooltip/TooltipComponent;)Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipComponent;",
		at = @At("HEAD"), cancellable = true)
	private static void flint$shulker(TooltipComponent component, CallbackInfoReturnable<ClientTooltipComponent> callback) {
		ClientTooltipComponent mine = Mc263Visuals.clientTooltip(component);
		if (mine != null) {
			callback.setReturnValue(mine);
		}
	}
}
