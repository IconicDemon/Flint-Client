package com.flintclient.client.mixin.feature;

import com.flintclient.client.compat.misc.HitConfirm;
import com.flintclient.client.control.platform.Mc263Animations;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Animations: each player's animation memory and the once-a-tick body-to-head request. Combat: the attack you make, which
 * your target's hurt flash then confirms ({@link HitConfirm}). On Minecraft 1.19.2 your hurt animation reaches Flint as a
 * hurt flash (LivingEntityMixin: 1.19.2 has no hurt animation with a direction, which 1.19.4 added), and how far the head
 * turns before the body follows is LivingEntity's tickHeadTurn's own bound (LivingEntityMixin: 1.19.2 has no
 * getMaxHeadRotationRelativeToBody).
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

	@Inject(method = "tick", at = @At("RETURN"))
	private void flint$animationsTick(CallbackInfo callback) {
		Mc263Animations.Data data = flint$animations;
		if (data != null) {
			data.tick();
		}
	}

	/** Your attack, as your client plays it out (MultiPlayerGameMode.attack, right after it tells the server). */
	@Inject(method = "attack", at = @At("HEAD"))
	private void flint$attacked(Entity target, CallbackInfo callback) {
		Player self = (Player) (Object) this;
		if (self.level.isClientSide && self == Minecraft.getInstance().player) {
			HitConfirm.attacked(target);
		}
	}
}
