package com.flintclient.client.control.platform.ambient;

import it.unimi.dsi.fastutil.doubles.Double2DoubleRBTreeMap;
import it.unimi.dsi.fastutil.doubles.Double2DoubleSortedMap;
import it.unimi.dsi.fastutil.doubles.DoubleComparators;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;

/**
 * The engine's sounds in Minecraft's sound manager: starts and stops them, notices when one has finished, and each tick
 * sets each one's volume, quieted by louder-priority sounds that mute others (under water mutes the rest). Game thread.
 * Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
final class AmbientSoundEngine {
	private final List<AmbientSound.Stream> sounds = new ArrayList<>();

	int playingCount() {
		return sounds.size();
	}

	private static SoundManager manager() {
		return Minecraft.getInstance().getSoundManager();
	}

	void tick() {
		Double2DoubleSortedMap mutes = new Double2DoubleRBTreeMap(DoubleComparators.OPPOSITE_COMPARATOR);
		for (AmbientSound.Stream sound : sounds) {
			double mute = sound.mute();
			if (mute > 0) {
				mutes.mergeDouble(sound.mutePriority(), mute, Math::max);
			}
		}
		Iterator<AmbientSound.Stream> it = sounds.iterator();
		while (it.hasNext()) {
			AmbientSound.Stream sound = it.next();
			boolean playing = manager().isActive(sound);
			if (!playing && !sound.hasPlayedOnce()) {
				continue;
			}
			if (sound.hasPlayedOnce() && !playing) {
				sound.onFinished();
				manager().stop(sound);
				it.remove();
				continue;
			}
			if (!sound.hasPlayedOnce()) {
				sound.setPlayedOnce();
			}
			if (mutes.isEmpty()) {
				sound.effectiveVolume = (float) sound.combinedVolume();
			} else {
				double mute = 0;
				for (it.unimi.dsi.fastutil.doubles.Double2DoubleMap.Entry entry : mutes.double2DoubleEntrySet()) {
					if (sound.mutePriority() < entry.getDoubleKey() || sound.mute() == 0) {
						mute = Math.max(entry.getDoubleValue(), mute);
					} else {
						break;
					}
				}
				sound.effectiveVolume = (float) (sound.combinedVolume() * (1 - mute));
			}
		}
	}

	void stop(AmbientSound.Stream sound) {
		manager().stop(sound);
		sounds.remove(sound);
	}

	void play(AmbientSound.Stream stream) {
		manager().play(stream);
		stream.onStart();
		sounds.add(stream);
	}

	void stopAll() {
		for (AmbientSound.Stream sound : List.copyOf(sounds)) {
			stop(sound);
			sound.onFinished();
		}
	}
}
