package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Glint;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Glint's tooltips: an item's tooltip set up with Glint's style and its enchantment lines' room for their icons. */
@Mixin(GuiGraphicsExtractor.class)
abstract class GlintTooltipMixin {
	@ModifyArg(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V", index = 1,
		at = @At(value = "INVOKE", target = McCompat.TOOLTIP_CALL))
	private List<Component> flint$glintLines(List<Component> lines, @Local(argsOnly = true) ItemStack stack) {
		return Mc263Glint.lines(stack, lines, stack.get(DataComponents.TOOLTIP_STYLE), stack.getTooltipImage().isPresent());
	}

	@ModifyArg(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V", index = 5,
		at = @At(value = "INVOKE", target = McCompat.TOOLTIP_CALL))
	private Identifier flint$glintStyle(Identifier style, @Local(argsOnly = true) ItemStack stack) {
		return Mc263Glint.style(stack, style);
	}
}
