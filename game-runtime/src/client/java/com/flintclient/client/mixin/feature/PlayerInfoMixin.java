package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Overlay;
import com.flintclient.client.control.platform.Mc263StoreCapes;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player's skin on your screen: the cape they equipped in the Flint Store, if any (control/platform/Mc263StoreCapes), then
 * Nick Hider (a default skin, on your screen only, for you and/or others; a hidden skin shows no Store cape either).
 *
 * <p>Flint decides last. Cape mods (the Capes mod with its OptiFine, LabyMod and MinecraftCapes capes, among others) put their
 * cape in this same skin with injections of their own; the Capes mod's is a cancellable one at the method's end that returns its
 * skin at once, so no hook beside it could come after it. The whole method is wrapped instead ({@code @WrapMethod} goes around
 * every other mod's injections into it), and Flint sees the skin as the mods left it: a player wearing a Flint Store cape gets
 * that cape (another mod's cape for them is hidden, and so is that mod's elytra texture: their wings are Minecraft's), and a
 * player without one keeps the skin the mods made, so the Capes mod's cape shows. Only one cape is ever on.
 *
 * <p>Minecraft's own skin (whose cape the wings of a Store cape without elytra art fall back to) is the return value as it
 * stands at priority 900: after low-priority hooks (the QA tour's Minecraft-cape stand-in, at 500), before the cape mods' (the
 * default, 1000).
 */
@Mixin(value = PlayerInfo.class, priority = 900)
abstract class PlayerInfoMixin {
	/** The skin as it stood before the cape mods' hooks, in the call under way; null when that hook didn't run. */
	@Unique
	private @Nullable PlayerSkin flint$minecraftSkin;

	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
	private PlayerSkin flint$minecraftSkin(PlayerSkin skin) {
		flint$minecraftSkin = skin;
		return skin;
	}

	@WrapMethod(method = "getSkin")
	private PlayerSkin flint$skin(Operation<PlayerSkin> original) {
		flint$minecraftSkin = null;
		PlayerSkin modded = original.call();
		PlayerSkin minecraft = flint$minecraftSkin;
		flint$minecraftSkin = null;
		PlayerInfo info = (PlayerInfo) (Object) this;
		return Mc263Overlay.skin(info, Mc263StoreCapes.skin(info, modded, minecraft == null ? modded : minecraft));
	}
}
