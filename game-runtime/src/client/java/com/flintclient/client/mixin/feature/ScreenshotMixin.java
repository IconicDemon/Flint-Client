package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Visuals;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import java.io.File;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Screenshot preview: notes the file each F2 screenshot is written to (the preview loads it once it's saved). */
@Mixin(Screenshot.class)
abstract class ScreenshotMixin {
	@ModifyReturnValue(method = "getFile", at = @At("RETURN"))
	private static File flint$file(File file) {
		return Mc263Visuals.screenshotFile(file);
	}
}
