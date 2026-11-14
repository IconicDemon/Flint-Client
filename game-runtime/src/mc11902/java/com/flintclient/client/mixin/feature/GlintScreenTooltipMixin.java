package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.TooltipStyle;
import com.flintclient.client.control.platform.Mc263Glint;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Glint's tooltips in inventories on Minecraft 1.19.2: a hovered slot's item tooltip goes through
 * Screen.renderTooltip(PoseStack, ItemStack, ...) (GlintTooltipMixin), except in the creative search tab, whose screen
 * builds its own lines (the item's group and tags added) and draws them itself: those get Glint's style and room for its
 * icons here.
 */
@Mixin(CreativeModeInventoryScreen.class)
abstract class GlintScreenTooltipMixin {
	@WrapOperation(method = "renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;II)V", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen;renderTooltip(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;Ljava/util/Optional;II)V"))
	private void flint$glint(CreativeModeInventoryScreen screen, PoseStack pose, List<Component> lines, Optional<TooltipComponent> image, int x,
		int y, Operation<Void> original, @Local(argsOnly = true) ItemStack stack) {
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
