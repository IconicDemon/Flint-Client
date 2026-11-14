package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.TooltipStyle;
import com.flintclient.client.control.platform.Mc263Glint;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Glint's tooltips on Minecraft 1.19.2: an item's tooltip drawn with Glint's style and its enchantment lines' room for their
 * icons. 1.19.2 items have no tooltip style of their own, so the style is Glint's or none, and it is handed to the background
 * through compat.TooltipStyle while the tooltip is drawn (compat.Tooltips draws it). Every item tooltip of a 1.19.2 screen
 * goes through Screen.renderTooltip(PoseStack, ItemStack, ...): a container's hovered slot and an item shown in chat alike
 * (the creative search tab's own: GlintScreenTooltipMixin).
 */
@Mixin(Screen.class)
abstract class GlintTooltipMixin {
	@WrapOperation(method = "renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;II)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/Screen;renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;Ljava/util/Optional;II)V"))
	private void flint$glint(Screen screen, PoseStack pose, List<Component> lines, Optional<TooltipComponent> image, int x, int y,
		Operation<Void> original, @Local(argsOnly = true) ItemStack stack) {
		ResourceLocation style = Mc263Glint.style(stack, null);
		List<Component> shown = Mc263Glint.lines(stack, lines, null, image.isPresent());
		ResourceLocation before = TooltipStyle.begin(style);
		try {
			original.call(screen, pose, shown, image, x, y);
		} finally {
			TooltipStyle.restore(before);
		}
	}
}
