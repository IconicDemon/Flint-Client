package com.flintclient.client.mixin.ncr;

import com.flintclient.client.compat.net.LoginSigning;
import com.flintclient.client.compat.net.NoKeys;
import com.flintclient.client.control.platform.Mc263ChatSafety;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Chat Reports' core on Minecraft 1.19.2: a key manager that keeps its keys to itself. Without a key the login hello carries
 * none, the login's challenge is answered unsigned, your player has no key and each message and command goes unsigned (1.19.2
 * asks the key manager for all of these, through getProfileKeyPairManager). Whether a connection's keys are kept back is decided
 * as it begins and kept for all of it (compat.net.LoginSigning): a server that got your key checks every message against it.
 * That holds for a world of your own too, which 1.19.2 always hands your key (its server drops it while No Chat Reports is on,
 * NcrChatSessionUpdateMixin), so it's decided as the world loads. Telemetry: 1.19.2 has no allowsTelemetry; its telemetry
 * sessions come from the user API service, which NcrUserApiServiceMixin switches off.
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
		if (LoginSigning.hidden()) {
			callback.setReturnValue(NoKeys.MANAGER);
		}
	}

	@Inject(method = "doWorldLoad", at = @At("HEAD"))
	private void flint$ownWorld(CallbackInfo callback) {
		LoginSigning.begin();
	}
}
