package com.flintclient.client.control.platform;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * One of Flint's interface sounds as Minecraft plays it, like vanilla's button click: UI category, heard at the
 * listener, no distance falloff, resolved to Flint's own generated sound (see {@link UiSounds}). Minecraft 1.21.11 (as 26.1) resolves a sound with resolve.
 */
final class UiSoundInstance extends AbstractSoundInstance {
	private final Sound flintSound;
	private final WeighedSoundEvents event;

	UiSoundInstance(Sound sound, WeighedSoundEvents event, float volume, RandomSource random) {
		super(sound.getLocation(), SoundSource.UI, random);
		this.flintSound = sound;
		this.event = event;
		this.volume = volume;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.relative = true;
	}

	@Override
	public WeighedSoundEvents resolve(SoundManager soundManager) {
		sound = flintSound;
		return event;
	}
}
