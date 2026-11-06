package com.flintclient.client.control.platform.ambient;

import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Mth;

/**
 * How a sound plays: its pitch, fades, how much it mutes other sounds, pauses and lengths, its channel. A condition can
 * set any of them; the narrowest one wins. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
class AmbientSoundProperties {
	@SerializedName("transition")
	public Integer transition;
	public Double pitch = 1.0;
	@SerializedName("fade-volume")
	public Double fadeVolume;
	@SerializedName("fade-in-volume")
	public Double fadeInVolume;
	@SerializedName("fade-out-volume")
	public Double fadeOutVolume;
	@SerializedName("fade-pitch")
	public Double fadePitch;
	@SerializedName("fade-in-pitch")
	public Double fadeInPitch;
	@SerializedName("fade-out-pitch")
	public Double fadeOutPitch;
	public Double mute;
	@SerializedName("mute-priority")
	public Double mutePriority;
	@SerializedName("random-offset")
	public boolean randomOffset = true;
	public AmbientCondition.MinMax pause;
	public AmbientCondition.MinMax length;
	@SerializedName("underwater-pitch")
	public Climbing underwaterPitch;
	public String channel;

	void init(AmbientEngine engine) {
		if (mute != null) {
			mute = Mth.clamp(mute, 0.0, 1.0);
		}
	}

	double getFadeInVolume(AmbientEngine engine) {
		return fadeInVolume != null ? fadeInVolume : fadeVolume != null ? fadeVolume : engine.fadeVolume();
	}

	double getFadeOutVolume(AmbientEngine engine) {
		return fadeOutVolume != null ? fadeOutVolume : fadeVolume != null ? fadeVolume : engine.fadeVolume();
	}

	double getFadeInPitch(AmbientEngine engine) {
		return fadeInPitch != null ? fadeInPitch : fadePitch != null ? fadePitch : engine.fadePitch();
	}

	double getFadeOutPitch(AmbientEngine engine) {
		return fadeOutPitch != null ? fadeOutPitch : fadePitch != null ? fadePitch : engine.fadePitch();
	}

	float getPitch(AmbientEnvironment env) {
		float base = pitch != null ? (float) pitch.doubleValue() : 1;
		return underwaterPitch != null ? base + (float) underwaterPitch.getValue(env.underwater) : base;
	}

	/** A value that climbs with something (the pitch with how deep you are under water). */
	static class Climbing {
		public double min = 0;
		public double max;
		@SerializedName("distance-factor")
		public double distanceFactor = 1;

		double getValue(double value) {
			if (max <= min) {
				max = min + 1;
			}
			return value / (max - min) * distanceFactor;
		}
	}
}
