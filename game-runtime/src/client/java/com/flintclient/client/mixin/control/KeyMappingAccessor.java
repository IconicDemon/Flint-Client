package com.flintclient.client.mixin.control;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The key a mapping is bound to right now. KeyMapping keeps it in a protected field with no getter (only
 * the default key and a display name are public), and the HUD's keystrokes need the real key to read the
 * physical key state. The prefixed name can't clash with another mod's accessor for the same field.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
	@Accessor("key")
	InputConstants.Key flint$boundKey();
}
