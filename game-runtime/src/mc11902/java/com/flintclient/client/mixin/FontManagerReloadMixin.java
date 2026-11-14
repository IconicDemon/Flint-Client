package com.flintclient.client.mixin;

import com.flintclient.client.compat.render.FontManagerBridge;
import com.flintclient.client.render.FontPreload;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft 1.19.2's font reload, which its FontManager runs in an anonymous reload listener (later versions in
 * FontManager.apply): {@link FontPreload} hears of it as FontManagerMixin's hooks do on later versions (before anything is
 * closed, what was prepared read the resources this reload replaces; afterwards, with the manager's own font-set lookup), and
 * Flint's dropped font sets' glyph providers are closed. {@code field_18216} is the listener's FontManager (javac's
 * {@code this$0}, which has no Mojang name).
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$1")
abstract class FontManagerReloadMixin {
	@Unique
	private static final String APPLY = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V";

	@Shadow
	@Final
	FontManager field_18216;

	@Inject(method = APPLY, at = @At("HEAD"))
	private void flint$reloadStarting(CallbackInfo callback) {
		FontPreload.reloadStarting();
	}

	@Inject(method = APPLY, at = @At("RETURN"))
	private void flint$reloaded(CallbackInfo callback) {
		FontManagerBridge fonts = (FontManagerBridge) field_18216;
		fonts.flint$reloaded();
		FontPreload.reloaded(fonts::flint$hasFontSet);
	}
}
