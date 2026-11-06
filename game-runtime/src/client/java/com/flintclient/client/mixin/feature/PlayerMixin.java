package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263Animations;
import com.flintclient.client.control.platform.Mc263Features;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hit Color's damage indicator: your hurt animation and the direction the server gave for it. Animations: each player's
 * animation memory, the once-a-tick body-to-head request, and how far the head turns before the body follows.
 */
@Mixin(Player.class)
abstract class PlayerMixin implements Mc263Animations.Holder {
	@Unique
	private Mc263Animations.@Nullable Data flint$animations;

	@Override
	public Mc263Animations.Data flint$animations() {
		Mc263Animations.Data data = flint$animations;
		if (data == null) {
			data = new Mc263Animations.Data();
			flint$animations = data;
		}
		return data;
	}

	@Inject(method = "animateHurt", at = @At("TAIL"))
	private void flint$hurt(float yaw, CallbackInfo callback) {
		Player self = (Player) (Object) this;
		if (self.level().isClientSide()) {
			Mc263Features.localHurt(self, yaw);
		}
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void flint$animationsTick(CallbackInfo callback) {
		Mc263Animations.Data data = flint$animations;
		if (data != null) {
			data.tick();
		}
	}

	@Inject(method = "getMaxHeadRotationRelativeToBody", at = @At("HEAD"), cancellable = true)
	private void flint$headTurn(CallbackInfoReturnable<Float> callback) {
		Float max = Mc263Animations.maxHeadRotation((Player) (Object) this);
		if (max != null) {
			callback.setReturnValue(max);
		}
	}
}
