package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263MoreMods;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TotemParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Totem: a totem particle's size, life and colours, as it is made. */
@Mixin(TotemParticle.class)
abstract class TotemParticleMixin extends SimpleAnimatedParticle {
	private TotemParticleMixin(ClientLevel level, double x, double y, double z, SpriteSet sprites, float gravity) {
		super(level, x, y, z, sprites, gravity);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void flint$tune(ClientLevel level, double x, double y, double z, double xa, double ya, double za, SpriteSet sprites, CallbackInfo callback) {
		Mc263MoreMods.totemParticle(new Mc263MoreMods.TotemParticleTuner() {
			@Override
			public void size(float factor) {
				quadSize *= factor;
			}

			@Override
			public void life(float factor) {
				lifetime = Math.max(1, Math.round(lifetime * factor));
			}

			@Override
			public void color(int rgb) {
				setColor(rgb);
			}
		});
	}
}
