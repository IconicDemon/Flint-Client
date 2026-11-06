package com.flintclient.legacy.mixin;

import net.minecraft.client.sound.system.SoundManager;
import net.minecraft.client.sound.system.SoundRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sound manager's registry of sound ids, for the menu's sound picker ({@code platform.Ids}). */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
	@Accessor("registry")
	SoundRegistry flint$registry();
}
