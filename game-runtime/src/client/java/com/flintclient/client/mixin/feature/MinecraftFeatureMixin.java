package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** GUI Scale: the scale Minecraft fits to the window, the module's while on (your own setting is never changed). */
@Mixin(Minecraft.class)
abstract class MinecraftFeatureMixin {
	@ModifyArg(method = "resizeGui", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;calculateScale(IZ)I"), index = 0)
	private int flint$guiScale(int scale) {
		return Mc263Features.guiScale(scale);
	}
}
