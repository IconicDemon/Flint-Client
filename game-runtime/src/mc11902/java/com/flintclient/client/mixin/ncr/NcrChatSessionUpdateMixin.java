package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.platform.Mc263ChatSafety;
import java.util.UUID;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.SignatureValidator;
import net.minecraft.world.entity.player.ProfilePublicKey;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Chat Reports in the world this client hosts: players' signing keys are ignored. Minecraft 1.19.2 has no chat sessions: a
 * player hands over their key in the login hello, and the server keeps it as the login is accepted. Not kept, the player has no
 * key in your world: its server then takes every message and command from them as unsigned (it reads no signature from a player
 * without a key), and tells everyone the player has none. The hello's key still checks the login's own encryption challenge, as
 * before, so a player's client needs no change.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(ServerLoginPacketListenerImpl.class)
abstract class NcrChatSessionUpdateMixin {
	@Inject(method = "validatePublicKey", at = @At("HEAD"), cancellable = true)
	private static void flint$ignore(ProfilePublicKey.@Nullable Data key, UUID player, SignatureValidator validator, boolean enforce,
		CallbackInfoReturnable<ProfilePublicKey> callback) {
		if (Mc263ChatSafety.hostIgnoresSigning()) {
			callback.setReturnValue(null);
		}
	}
}
