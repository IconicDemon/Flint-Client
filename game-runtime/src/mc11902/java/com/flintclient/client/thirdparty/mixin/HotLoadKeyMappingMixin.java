package com.flintclient.client.thirdparty.mixin;

import com.flintclient.client.hotload.HotLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fabric API's key binding registry (1.19.x's Fabric API, before it took Mojang's names: KeyBindingRegistryImpl) refuses a key
 * once the game's options exist ("GameOptions has already been initialised"). While {@link HotLoader} runs a hot-loaded mod's
 * client entrypoints, it accepts the key as at start; the hot loader then adds it to {@code Options.keyMappings} itself. Any
 * other late registration is still refused, as before. The field read is Minecraft's (remapped); the method is Fabric's.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.keybinding.KeyBindingRegistryImpl")
abstract class HotLoadKeyMappingMixin {
	@Redirect(method = "registerKeyBinding", remap = false, require = 0,
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;options:Lnet/minecraft/client/Options;", remap = true))
	private static Options flint$optionsUnlessHotLoading(Minecraft minecraft) {
		return HotLoader.initialising() ? null : minecraft.options;
	}
}
