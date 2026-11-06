package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263LookMods;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Glint: potions glint with Shiny potions on. */
@Mixin(ItemStack.class)
abstract class ItemStackFoilMixin {
	@ModifyReturnValue(method = "hasFoil", at = @At("RETURN"))
	private boolean flint$shiny(boolean foil) {
		return foil || Mc263LookMods.shinyPotion((ItemStack) (Object) this);
	}
}
