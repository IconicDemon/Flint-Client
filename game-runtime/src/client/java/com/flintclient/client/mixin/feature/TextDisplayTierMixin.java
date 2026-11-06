/*
 * Based on TierTagger (https://github.com/mctiers-dev/TierTagger), Copyright (c) 2025 MCTiers, mctiers.com.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Tiers;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tier Tagger on a server's text-display nametag riding a player: the line with their name gets their tier. */
@Mixin(DisplayRenderer.TextDisplayRenderer.class)
abstract class TextDisplayTierMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display$TextDisplay;Lnet/minecraft/client/renderer/entity/state/TextDisplayEntityRenderState;F)V",
		at = @At("RETURN"))
	private void flint$tier(Display.TextDisplay entity, TextDisplayEntityRenderState state, float partialTick, CallbackInfo callback) {
		Display.TextDisplay.CachedInfo changed = Mc263Tiers.textDisplay(entity, state.cachedInfo);
		if (changed != null) {
			state.cachedInfo = changed;
		}
	}
}
