package com.flintclient.client.mixin.bridge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The game's key list set anew (the hot loader adds a hot-loaded mod's keys, {@code hotload/HotLoader}): on 1.21.x the field's
 * runtime name is the intermediary one, so it is set through this accessor rather than by its name.
 */
@Mixin(Options.class)
public interface OptionsKeysAccessor {
	@Mutable
	@Accessor("keyMappings")
	void flint$keyMappings(KeyMapping[] keys);
}
