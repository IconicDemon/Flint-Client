package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.platform.Mc263Tools;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Custom Advancements Screen: opening Minecraft's advancements screen opens Flint's list instead while the mod is on. */
@Mixin(targets = McCompat.SCREENS_CLASS)
abstract class AdvancementsScreenSwapMixin {
	@ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
	private Screen flint$advancements(Screen screen) {
		return Mc263Tools.screen(screen);
	}
}
