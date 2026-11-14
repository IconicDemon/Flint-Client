package com.flintclient.client.mixin.control;

import com.flintclient.client.FlintLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft's friends key (O) never pops its "turn on the friends list?" confirm over the world by itself. Keys typed into a
 * game that just took over (an Instant Swap's new game, or a world that finished loading while the player was still typing
 * elsewhere) land in the world and an O opened that confirm. Now, in the world (no screen open): within 5 s of joining a world
 * the key does nothing, and while the friends list is off it does nothing either (the player turns it on from Friends'
 * "Minecraft Friends" button or Online options, where the confirm shows because they asked). With the list on, after the first
 * seconds, the key opens and closes the list as in vanilla; from the title and pause screens it is vanilla's too.
 */
@Mixin(Minecraft.class)
abstract class FriendsKeyMixin {
	@Unique
	private static final long FLINT_JOIN_GRACE_NANOS = 5_000_000_000L;
	@Unique
	private @Nullable ClientLevel flint$lastLevel;
	@Unique
	private long flint$joinedAt;

	@Inject(method = "tick", at = @At("HEAD"))
	private void flint$noteJoin(CallbackInfo ci) {
		ClientLevel level = ((Minecraft) (Object) this).level;
		if (level != flint$lastLevel) {
			flint$lastLevel = level;
			flint$joinedAt = System.nanoTime();
		}
	}

	@Inject(method = "toggleFriendsScreen", at = @At("HEAD"), cancellable = true, require = 0)
	private void flint$noConfirmOverTheWorld(CallbackInfoReturnable<Boolean> cir) {
		Minecraft minecraft = (Minecraft) (Object) this;
		if (minecraft.gui.screen() != null || minecraft.level == null) {
			return;
		}
		boolean justJoined = System.nanoTime() - flint$joinedAt < FLINT_JOIN_GRACE_NANOS;
		boolean listOff;
		try {
			listOff = !minecraft.getPlayerSocialManager().isFriendListEnabled();
		} catch (RuntimeException | LinkageError e) {
			listOff = true;
		}
		if (justJoined || listOff) {
			FlintLog.LOGGER.info("Flint: the friends key in the world was ignored ({})", justJoined ? "just joined" : "friends list off");
			cir.setReturnValue(false);
		}
	}
}
