package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hit Color's damage indicator: your hurt animation and the direction the server gave for it. */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "animateHurt", at = @At("TAIL"))
	private void flint$hurt(float yaw, CallbackInfo callback) {
		Player self = (Player) (Object) this;
		if (self.level().isClientSide()) {
			Mc263Features.localHurt(self, yaw);
		}
	}
}
