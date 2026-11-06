package com.flintclient.legacy.mixin;

import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a key is held by its own input (what {@code isPressed} reports before Toggle Sprint holds it). */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
	@Accessor("pressed")
	boolean flint$pressed();
}
