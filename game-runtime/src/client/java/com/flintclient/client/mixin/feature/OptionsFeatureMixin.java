package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Menu Blur: the menu background blur Minecraft reads, the module's while on (your own setting is never changed). */
@Mixin(Options.class)
abstract class OptionsFeatureMixin {
	@ModifyReturnValue(method = "getMenuBackgroundBlurriness", at = @At("RETURN"))
	private int flint$menuBlur(int blur) {
		return Mc263Features.menuBlur(blur);
	}
}
