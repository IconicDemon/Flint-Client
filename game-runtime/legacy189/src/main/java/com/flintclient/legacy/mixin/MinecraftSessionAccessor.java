package com.flintclient.legacy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Session;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's session (the account it plays as): the main menu's account card switches it ({@code module.account.SessionSwitch}). */
@Mixin(Minecraft.class)
public interface MinecraftSessionAccessor {
	@Mutable
	@Accessor("session")
	void flint$setSession(Session session);
}
