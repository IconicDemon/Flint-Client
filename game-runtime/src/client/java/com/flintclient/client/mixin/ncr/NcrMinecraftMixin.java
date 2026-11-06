package com.flintclient.client.mixin.ncr;

import com.flintclient.client.control.model.mods.ServerMods.NoChatReports;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Chat Reports: Minecraft's chat signing keys stay hidden (an empty key manager) until signing is allowed for the session,
 * so the client starts no chat session and signs nothing; and telemetry is off with Disable telemetry.
 *
 * <p>Adapted from No Chat Reports (Aizistral, WTFPL; META-INF/licenses/WTFPL-NoChatReports.txt).
 */
@Mixin(Minecraft.class)
abstract class NcrMinecraftMixin implements Mc263ChatSafety.Keys {
	@Shadow
	@Final
	private ProfileKeyPairManager profileKeyPairManager;

	@Override
	public ProfileKeyPairManager flint$realKeys() {
		return profileKeyPairManager;
	}

	@Inject(method = "getProfileKeyPairManager", at = @At("HEAD"), cancellable = true)
	private void flint$hideKeys(CallbackInfoReturnable<ProfileKeyPairManager> callback) {
		if (Mc263ChatSafety.hideKeys()) {
			callback.setReturnValue(ProfileKeyPairManager.EMPTY_KEY_MANAGER);
		}
	}

	@Inject(method = "allowsTelemetry", at = @At("HEAD"), cancellable = true)
	private void flint$telemetry(CallbackInfoReturnable<Boolean> callback) {
		if (Mc263ChatSafety.with(NoChatReports.DISABLE_TELEMETRY)) {
			callback.setReturnValue(false);
		}
	}
}
