package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.TooltipStyle;
import com.flintclient.client.control.platform.Mc263Glint;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Glint's tooltips on Minecraft 1.21.1: an item's tooltip drawn with Glint's style and its enchantment lines' room for their
 * icons. 1.21.1 items have no tooltip style of their own, so the style is Glint's or none, and it is handed to the background
 * through compat.TooltipStyle while the tooltip is drawn.
 */
@Mixin(GuiGraphics.class)
abstract class GlintTooltipMixin {
	@WrapOperation(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphics;renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;II)V"))
	private void flint$glint(GuiGraphics graphics, Font font, List<Component> lines, Optional<TooltipComponent> image, int x, int y,
		Operation<Void> original, @Local(argsOnly = true) ItemStack stack) {
		ResourceLocation style = Mc263Glint.style(stack, null);
		List<Component> shown = Mc263Glint.lines(stack, lines, null, image.isPresent());
		ResourceLocation before = TooltipStyle.begin(style);
		try {
			original.call(graphics, font, shown, image, x, y);
		} finally {
			TooltipStyle.restore(before);
		}
	}
}
