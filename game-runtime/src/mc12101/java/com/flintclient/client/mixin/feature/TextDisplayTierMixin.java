/*
 * Based on TierTagger (https://github.com/mctiers-dev/TierTagger), Copyright (c) 2025 MCTiers, mctiers.com.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Tiers;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Tier Tagger on a server's text-display nametag riding a player, on Minecraft 1.21.1: the line with their name gets their
 * tier. 1.21.1 reads the display's split lines while drawing it (renderInner), so the lines are swapped there.
 */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
abstract class TextDisplayTierMixin {
	@ModifyExpressionValue(method = "renderInner(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/world/entity/Display$TextDisplay$TextRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Display$TextDisplay;cacheDisplay(Lnet/minecraft/world/entity/Display$TextDisplay$LineSplitter;)Lnet/minecraft/world/entity/Display$TextDisplay$CachedInfo;"))
	private Display.TextDisplay.CachedInfo flint$tier(Display.TextDisplay.CachedInfo info, @Local(argsOnly = true) Display.TextDisplay entity) {
		Display.TextDisplay.CachedInfo changed = Mc263Tiers.textDisplay(entity, info);
		return changed != null ? changed : info;
	}
}
