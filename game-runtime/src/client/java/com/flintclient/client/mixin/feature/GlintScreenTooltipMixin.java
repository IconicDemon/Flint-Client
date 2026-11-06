package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Glint;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Glint's tooltips in inventories: the hovered slot's item tooltip set up with Glint's style and room for its icons. */
@Mixin(AbstractContainerScreen.class)
abstract class GlintScreenTooltipMixin {
	@ModifyArg(method = McCompat.CONTAINER_TOOLTIP, index = 1, at = @At(value = "INVOKE", target = McCompat.TOOLTIP_CALL))
	private List<Component> flint$glintLines(List<Component> lines, @Local ItemStack stack) {
		return Mc263Glint.lines(stack, lines, stack.get(DataComponents.TOOLTIP_STYLE), stack.getTooltipImage().isPresent());
	}

	@ModifyArg(method = McCompat.CONTAINER_TOOLTIP, index = 5, at = @At(value = "INVOKE", target = McCompat.TOOLTIP_CALL))
	private Identifier flint$glintStyle(Identifier style, @Local ItemStack stack) {
		return Mc263Glint.style(stack, style);
	}
}
