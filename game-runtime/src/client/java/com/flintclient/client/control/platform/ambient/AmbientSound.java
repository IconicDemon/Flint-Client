package com.flintclient.client.control.platform.ambient;

import com.google.gson.annotations.JsonAdapter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * One sound of a region: its files (sound events), its own condition, its categories. While it should be heard it plays
 * one of its files, fades its volume and pitch toward where they should be, pauses between plays, and (for long ones)
 * cross-fades into the next file. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
final class AmbientSound extends AmbientCondition {
	private static final Random RANDOM = new Random();
	private static final List<Field> COPY_FIELDS = new ArrayList<>();

	static {
		for (Class<?> c = AmbientSound.class; c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (!Modifier.isPrivate(field.getModifiers()) && !Modifier.isFinal(field.getModifiers()) && !Modifier.isStatic(field.getModifiers())) {
					field.setAccessible(true);
					COPY_FIELDS.add(field);
				}
			}
		}
	}

	transient double volumeSetting = 1;
	public String name;
	transient String fullName;
	public Identifier[] files;
	public double[] chances;
	@JsonAdapter(AmbientEntityCondition.StringJson.class)
	public String[] category;
	transient @Nullable Stream stream1;
	transient @Nullable Stream stream2;
	transient boolean active;
	transient float cachedAimedConditionVolume;
	transient float cachedAimedOutputVolume;
	transient AmbientVolume aimedVolume;
	transient float currentConditionVolume;
	transient float currentOutputVolume;
	transient float aimedPitch;
	transient int transition;
	transient int transitionTime;
	transient int pauseTimer = -1;
	transient AmbientSoundProperties currentProperties;
	transient AmbientEngine engine;
	transient List<AmbientSoundCategory> categories;

	static SoundSource soundSource(@Nullable String name) {
		if (AmbientSounds.useMaster()) {
			return SoundSource.MASTER;
		}
		if (name == null) {
			return SoundSource.AMBIENT;
		}
		for (SoundSource source : SoundSource.values()) {
			if (source.getName().equals(name)) {
				return source;
			}
		}
		return SoundSource.AMBIENT;
	}

	@Override
	void init(AmbientEngine engine) {
		if (files == null || files.length == 0) {
			throw new IllegalStateException("Invalid sound " + name + " which does not contain any sound file");
		}
		super.init(engine);
		this.engine = engine;
		if (chances == null) {
			chances = new double[files.length];
			Arrays.fill(chances, 1.0 / files.length);
		} else if (chances.length != files.length) {
			double[] next = new double[files.length];
			for (int i = 0; i < next.length; i++) {
				next[i] = chances.length > i ? chances[i] : 1.0 / files.length;
			}
			chances = next;
		}
		if (category != null && category.length > 0) {
			categories = new ArrayList<>();
			for (String c : category) {
				AmbientSoundCategory cat = engine.getSoundCategory(c);
				if (cat != null) {
					categories.add(cat);
				} else {
					AmbientSounds.LOGGER.error("Ambient Sounds: no sound category {} for {}", c, fullName);
				}
			}
		}
	}

	private int randomFile() {
		return files.length == 1 ? 0 : RANDOM.nextInt(files.length);
	}

	private int randomFileExcept(int i) {
		if (files.length == 2) {
			return i == 0 ? 1 : 0;
		}
		int index = RANDOM.nextInt(files.length - 1);
		return index >= i ? index + 1 : index;
	}

	boolean fastTick(AmbientEnvironment env) {
		if (currentConditionVolume < cachedAimedConditionVolume) {
			currentConditionVolume += (float) Math.min(currentProperties.getFadeInVolume(engine), cachedAimedConditionVolume - currentConditionVolume);
		} else if (currentConditionVolume > cachedAimedConditionVolume) {
			currentConditionVolume -= (float) Math.min(currentProperties.getFadeOutVolume(engine), currentConditionVolume - cachedAimedConditionVolume);
		}
		if (currentOutputVolume < cachedAimedOutputVolume) {
			currentOutputVolume += (float) Math.min(currentProperties.getFadeInVolume(engine), cachedAimedOutputVolume - currentOutputVolume);
		} else if (currentOutputVolume > cachedAimedOutputVolume) {
			currentOutputVolume -= (float) Math.min(currentProperties.getFadeOutVolume(engine), currentOutputVolume - cachedAimedOutputVolume);
		}
		if (isPlaying()) {
			if (inTransition()) {
				stream1.transitionVolume = 1.0 - (double) transition / transitionTime;
				stream2.transitionVolume = (double) transition / transitionTime;
				if (transition >= transitionTime) {
					engine.soundEngine.stop(stream1);
					stream1 = stream2;
					stream2 = null;
				}
				transition++;
			} else {
				if (stream1.duration == -1 && currentProperties.length != null) {
					stream1.duration = (int) currentProperties.length.randomValue();
				} else if (stream1.duration > 0 && currentProperties.length == null) {
					stream1.duration = -1;
				}
				stream1.transitionVolume = 1.0;
				if (currentProperties.length != null) {
					if (currentProperties.pause != null || files.length <= 1) {
						int fadeOutTime = (int) Math.ceil(cachedAimedConditionVolume / currentProperties.getFadeOutVolume(engine));
						if (stream1.remaining() <= 0) {
							engine.soundEngine.stop(stream1);
							stream1 = null;
							pauseTimer = -1;
						} else if (fadeOutTime > stream1.remaining()) {
							stream1.transitionVolume = stream1.remaining() / fadeOutTime;
						}
					} else if (stream1.remaining() <= 0) {
						transition = 0;
						stream2 = playTransition(randomFileExcept(stream1.index));
						transitionTime = currentProperties.transition != null ? currentProperties.transition : 60;
					}
				}
			}
			if (stream1 != null) {
				stream1.pitch = towards(stream1.pitch);
				stream1.ticksPlayed++;
			}
			if (stream2 != null) {
				stream2.pitch = towards(stream2.pitch);
				stream2.ticksPlayed++;
			}
		} else {
			if (stream2 != null) {
				engine.soundEngine.stop(stream2);
				stream2 = null;
			}
			if (pauseTimer == -1 && currentProperties.pause != null) {
				pauseTimer = (int) currentProperties.pause.randomValue();
			}
			if (pauseTimer <= 0) {
				stream1 = play(randomFile());
			} else {
				pauseTimer--;
			}
		}
		return isAudible();
	}

	private double towards(double pitch) {
		if (pitch < aimedPitch) {
			return pitch + Math.min(currentProperties.getFadeInPitch(engine), aimedPitch - pitch);
		}
		if (pitch > aimedPitch) {
			return pitch - Math.min(currentProperties.getFadeOutPitch(engine), pitch - aimedPitch);
		}
		return pitch;
	}

	@Override
	@Nullable AmbientSelection value(AmbientEnvironment env) {
		if (volumeSetting == 0) {
			return null;
		}
		AmbientSelection value = super.value(env);
		if (value != null && categories != null) {
			List<AmbientSelection> collected = new ArrayList<>();
			for (AmbientSoundCategory cat : categories) {
				if (cat.selection == null) {
					return null;
				}
				collected.add(cat.selection);
			}
			value = new AmbientSelection.Multi(value, collected);
		}
		return value;
	}

	boolean isAudible() {
		return cachedAimedConditionVolume > 0 || currentConditionVolume > 0 || currentOutputVolume > 0;
	}

	boolean tick(AmbientEnvironment env, @Nullable AmbientSelection selection) {
		AmbientSelection own = selection == null ? null : value(env);
		if (own != null) {
			AmbientSelection last = selection.last();
			last.subSelection = own;
			aimedVolume = selection;
			cachedAimedConditionVolume = (float) selection.conditionVolume();
			cachedAimedOutputVolume = (float) (aimedVolume.volume() * volumeSetting * env.dimension.volumeSetting * AmbientSounds.masterVolume());
			currentProperties = selection.getProperties();
			last.subSelection = null;
			aimedPitch = Mth.clamp(currentProperties.getPitch(env), 0.5F, 2.0F);
		} else {
			aimedVolume = AmbientVolume.SILENT;
			cachedAimedConditionVolume = 0;
			cachedAimedOutputVolume = 0;
		}
		return isAudible();
	}

	private Stream play(int index) {
		Stream stream = new Stream(index);
		stream.pitch = aimedPitch;
		if (currentProperties.length != null) {
			stream.duration = (int) currentProperties.length.randomValue();
		}
		engine.soundEngine.play(stream);
		return stream;
	}

	private Stream playTransition(int index) {
		Stream stream = new Stream(index);
		stream.pitch = aimedPitch;
		if (currentProperties.length != null) {
			stream.duration = (int) currentProperties.length.randomValue();
		}
		stream.transitionVolume = 0;
		stream.effectiveVolume = (float) stream.combinedVolume();
		engine.soundEngine.play(stream);
		return stream;
	}

	boolean isPlaying() {
		return stream1 != null;
	}

	boolean inTransition() {
		return stream1 != null && stream2 != null;
	}

	boolean isActive() {
		return active;
	}

	void activate() {
		active = true;
	}

	void deactivate() {
		active = false;
		if (stream1 != null) {
			engine.soundEngine.stop(stream1);
			stream1 = null;
		}
		if (stream2 != null) {
			engine.soundEngine.stop(stream2);
			stream2 = null;
		}
	}

	void onSoundFinished() {
		if (stream1 != null && stream1.finished) {
			stream1 = null;
			pauseTimer = -1;
		} else {
			stream2 = null;
		}
	}

	boolean loop() {
		return currentProperties.length != null || currentProperties.pause == null && files.length == 1;
	}

	AmbientSound copy() {
		AmbientSound copy = new AmbientSound();
		for (Field field : COPY_FIELDS) {
			try {
				field.set(copy, field.get(this));
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
		}
		return copy;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder(name);
		if (stream1 != null) {
			builder.append('[').append(stream1).append(']');
		}
		if (stream2 != null) {
			builder.append('[').append(stream2).append(']');
		}
		if (inTransition()) {
			builder.append("t: ").append(transition).append('/').append(transitionTime);
		}
		return builder.toString();
	}

	/** One play of one file: a Minecraft sound, in your head (no position), its volume and pitch changed each tick. */
	final class Stream extends AbstractTickableSoundInstance {
		final int index;
		final Identifier file;
		float effectiveVolume;
		double transitionVolume = 1;
		double pitch;
		int duration = -1;
		int ticksPlayed;
		boolean finished;
		private boolean playedOnce;
		final boolean randomOffset;

		Stream(int index) {
			super(SoundEvent.createVariableRangeEvent(files[index]), soundSource(currentProperties.channel), RandomSource.create());
			this.index = index;
			this.file = files[index];
			this.effectiveVolume = (float) combinedVolume();
			this.randomOffset = currentProperties.randomOffset && AmbientSounds.playWithOffset() && AmbientSound.this.loop();
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.relative = true;
			this.x = 0;
			this.y = 0;
			this.z = 0;
		}

		int remaining() {
			return duration - ticksPlayed;
		}

		double conditionVolume() {
			return currentConditionVolume;
		}

		double combinedVolume() {
			return currentOutputVolume * transitionVolume;
		}

		double mute() {
			return currentProperties.mute == null ? 0 : currentProperties.mute * conditionVolume();
		}

		double mutePriority() {
			return currentProperties.mutePriority != null ? currentProperties.mutePriority : 0;
		}

		void onStart() {
			finished = false;
			playedOnce = false;
		}

		void onFinished() {
			finished = true;
			onSoundFinished();
		}

		boolean hasPlayedOnce() {
			return playedOnce;
		}

		void setPlayedOnce() {
			playedOnce = true;
		}

		String region() {
			return fullName == null ? name : fullName;
		}

		@Override
		public boolean isLooping() {
			return loop();
		}

		@Override
		public float getVolume() {
			return effectiveVolume;
		}

		@Override
		public float getPitch() {
			return (float) pitch;
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
		}

		@Override
		public String toString() {
			return "l:" + file + ",v:" + String.format(java.util.Locale.ROOT, "%.2f(%.2f)", effectiveVolume, conditionVolume()) + ",i:" + index
				+ ",p:" + String.format(java.util.Locale.ROOT, "%.2f", pitch) + ",t:" + ticksPlayed + ",d:" + duration;
		}
	}
}
