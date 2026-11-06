package com.flintclient.client.mixin.feature;

import com.flintclient.client.control.platform.Mc263SoundFilters;
import com.flintclient.client.control.platform.sound.SoundPhysics;
import com.flintclient.client.control.platform.sound.SoundStart;
import com.flintclient.client.control.platform.sound.TaggedChannel;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A sound channel as Flint's sound mods see it: its place and whether it's relative to you, which sound it plays (tagged
 * by {@code SoundEngineTagMixin}), and, just before it plays, Sound Physics' echo and muffling (or Sound Filters', while
 * Sound Physics is off) and Ambient Sounds' random start. Sound Physics also stretches its fade-out distance.
 */
@Mixin(Channel.class)
abstract class SoundChannelMixin implements TaggedChannel {
	@Shadow
	@Final
	private int source;
	@Unique
	private double flint$x;
	@Unique
	private double flint$y;
	@Unique
	private double flint$z;
	@Unique
	private boolean flint$relative;
	@Unique
	private @Nullable Identifier flint$sound;
	@Unique
	private @Nullable SoundSource flint$category;
	@Unique
	private boolean flint$randomOffset;

	@Inject(method = "setSelfPosition", at = @At("HEAD"))
	private void flint$where(Vec3 position, CallbackInfo callback) {
		flint$x = position.x;
		flint$y = position.y;
		flint$z = position.z;
	}

	@Inject(method = "setRelative", at = @At("HEAD"))
	private void flint$relative(boolean relative, CallbackInfo callback) {
		flint$relative = relative;
	}

	@Inject(method = "play", at = @At("HEAD"))
	private void flint$filters(CallbackInfo callback) {
		if (flint$randomOffset) {
			SoundStart.randomOffset(source);
		}
		if (!SoundPhysics.play(this, flint$x, flint$y, flint$z)) {
			Mc263SoundFilters.play(source, flint$x, flint$y, flint$z, flint$relative);
		}
	}

	@ModifyVariable(method = "linearAttenuation", at = @At("HEAD"), ordinal = 0, argsOnly = true)
	private float flint$attenuation(float distance) {
		return SoundPhysics.attenuation(distance);
	}

	@Inject(method = "linearAttenuation", at = @At("RETURN"))
	private void flint$referenceDistance(float distance, CallbackInfo callback) {
		SoundPhysics.afterAttenuation(source, distance);
	}

	@Override
	public int flint$source() {
		return source;
	}

	@Override
	public void flint$tag(Identifier sound, SoundSource category, boolean randomOffset) {
		flint$sound = sound;
		flint$category = category;
		flint$randomOffset = randomOffset;
	}

	@Override
	public @Nullable Identifier flint$sound() {
		return flint$sound;
	}

	@Override
	public @Nullable SoundSource flint$category() {
		return flint$category;
	}

	@Override
	public boolean flint$randomOffset() {
		return flint$randomOffset;
	}
}
