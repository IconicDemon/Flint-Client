package com.flintclient.client.control.platform.ambient;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A place where certain sounds play (a forest, a cave, under water, a storm): a condition, and its sounds, which play
 * while it holds and fade out when it stops. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
final class AmbientRegion extends AmbientCondition {
	public String name;
	public AmbientStackType stack = AmbientStackType.overwrite;
	public AmbientSound[] sounds;
	@SerializedName("sound-collections")
	public String[] soundCollections;
	transient double volumeSetting = 1;
	transient boolean active;
	transient LinkedHashMap<String, AmbientSound> loadedSounds;
	transient List<AmbientSound> playing = new ArrayList<>();
	transient @Nullable AmbientDimension dimension;

	void load() {
		if (sounds != null) {
			loadedSounds = new LinkedHashMap<>();
			for (AmbientSound sound : sounds) {
				loadedSounds.put(sound.name, sound);
			}
		}
	}

	@Override
	String regionName() {
		return name;
	}

	@Override
	void init(AmbientEngine engine) {
		super.init(engine);
		if (loadedSounds != null) {
			for (AmbientSound sound : loadedSounds.values()) {
				sound.init(engine);
			}
		}
		if (soundCollections != null) {
			if (loadedSounds == null) {
				loadedSounds = new LinkedHashMap<>();
			}
			engine.consumeSoundCollections(soundCollections, sound -> loadedSounds.put(sound.name, sound.copy()));
		}
	}

	@Override
	@Nullable AmbientSelection value(AmbientEnvironment env) {
		if (dimension != null && dimension != env.dimension || volumeSetting == 0) {
			return null;
		}
		AmbientSelection selection = super.value(env);
		if (selection != null) {
			selection.mulSetting(volumeSetting);
		}
		return selection;
	}

	boolean fastTick(AmbientEnvironment env) {
		Iterator<AmbientSound> it = playing.iterator();
		while (it.hasNext()) {
			AmbientSound sound = it.next();
			if (!sound.fastTick(env)) {
				sound.deactivate();
				it.remove();
			}
		}
		return !playing.isEmpty();
	}

	boolean tick(AmbientEnvironment env) {
		if (loadedSounds == null) {
			return false;
		}
		AmbientSelection selection = value(env);
		for (AmbientSound sound : loadedSounds.values()) {
			if (sound.tick(env, selection)) {
				if (!sound.isActive()) {
					sound.activate();
					playing.add(sound);
				}
			} else if (sound.isActive()) {
				sound.deactivate();
				playing.remove(sound);
			}
		}
		return !playing.isEmpty();
	}

	boolean isActive() {
		return active;
	}

	void activate() {
		active = true;
	}

	void deactivate() {
		active = false;
		for (AmbientSound sound : playing) {
			sound.deactivate();
		}
		playing.clear();
	}

	@Override
	public String toString() {
		return name + ", playing: " + playing.size();
	}
}
