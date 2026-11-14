package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.world.entity.player.ProfilePublicKey;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * No Chat Reports in the world this client hosts: players' signing keys are ignored. Minecraft 1.19 (1.19.0) has no chat
 * sessions: a player hands over their key in the login hello, and the server makes the player with it as the login is accepted.
 * Made without it, the player has no key in your world: its server then takes every message and command from them as unsigned
 * (it checks no signature from a player without a key), and tells everyone the player has none. 1.19.0's login checks its
 * encryption challenge against the hello's key (a client with a key signs the challenge rather than encrypting it), so the key
 * is kept for the login itself and dropped only as the player is made: a player's client needs no change.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerLoginPacketListenerImpl.class)
abstract class NcrChatSessionUpdateMixin {
	@ModifyArg(method = "handleAcceptedLogin", index = 1, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/players/PlayerList;getPlayerForLogin(Lcom/mojang/authlib/GameProfile;Lnet/minecraft/world/entity/player/ProfilePublicKey;)Lnet/minecraft/server/level/ServerPlayer;"))
	private @Nullable ProfilePublicKey flint$ignore(@Nullable ProfilePublicKey key) {
		return Mc263ChatSafety.hostIgnoresSigning() ? null : key;
	}
}
