package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.sound.SoundGate;
import com.flintclient.client.control.sound.ToneSynth;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.ConstantFloat;
import org.jspecify.annotations.Nullable;

/**
 * Plays the Control Center's open and close sounds ({@link ToneSynth}) through Minecraft's sound engine,
 * so they follow the player's master and UI volumes and output device like any interface sound.
 *
 * <p>Minecraft 26.3 only plays Ogg Vorbis files found in resource packs, so two pieces stand in for the
 * missing files. The sound instance answers its own sound event instead of looking one up, which would
 * fail for want of a sounds.json; and {@code SoundBufferLibraryMixin} answers the engine's request for
 * {@code flintclient:sounds/ui/open.ogg} (Minecraft's file path for the sound {@code flintclient:ui/open})
 * with the generated samples. The samples are synthesised once, on the first play.
 *
 * <p>A new sound stops the previous one, so quick open and close never overlap; and a sound asked for within 150 ms of
 * the last one played doesn't play at all ({@link SoundGate}), so turning the Control Center round mid-close lets the
 * sound already playing finish instead of restarting it. Render thread only.
 */
public final class UiSounds {
	public static final Identifier OPEN = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "ui/open");
	public static final Identifier CLOSE = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "ui/close");
	// Where the engine looks for each sound's data: flintclient:sounds/ui/open.ogg and .../close.ogg.
	static final Identifier OPEN_FILE = Sound.SOUND_LISTER.idToFile(OPEN);
	static final Identifier CLOSE_FILE = Sound.SOUND_LISTER.idToFile(CLOSE);
	private static final AudioFormat FORMAT = new AudioFormat(ToneSynth.SAMPLE_RATE, 16, 1, true, false);
	// Nothing about these sounds is random; the instance only needs a source to pass around.
	private static final RandomSource RANDOM = SoundInstance.createUnseededRandom();

	private static @Nullable Cue openCue;
	private static @Nullable Cue closeCue;
	private static @Nullable SoundInstance playing;
	private static final SoundGate GATE = new SoundGate();

	private UiSounds() {
	}

	/** Plays the open sound at {@code volume} from 0 to 1 (0 plays nothing). */
	public static void playOpen(float volume) {
		play(open(), volume);
	}

	/** Plays the close sound at {@code volume} from 0 to 1 (0 plays nothing). */
	public static void playClose(float volume) {
		play(close(), volume);
	}

	/**
	 * For {@code SoundBufferLibraryMixin}, through {@code ControlCenter.soundBuffer}: a ready buffer for one
	 * of Flint's sound files, or null for any other file. Called only when the engine has no buffer for that
	 * file, that is on its first play and again after the engine has released its buffers (a sound reload or
	 * device change). A buffer is handed to OpenAL once and then forgets its samples, so each call makes a
	 * new one from the kept PCM.
	 */
	public static @Nullable CompletableFuture<SoundBuffer> buffer(Identifier file) {
		ByteBuffer data = samples(file);
		return data == null ? null : CompletableFuture.completedFuture(new SoundBuffer(data, FORMAT));
	}

	/** The samples of one of Flint's sound files in new native memory, ready for OpenAL; null for any other file. */
	static @Nullable ByteBuffer samples(Identifier file) {
		Cue cue = file.equals(OPEN_FILE) ? open() : file.equals(CLOSE_FILE) ? close() : null;
		if (cue == null) {
			return null;
		}
		// OpenAL reads 16-bit samples in the machine's byte order from native memory. The declared format is
		// little-endian, as for vanilla's decoded sounds: every machine Minecraft runs on is.
		ByteBuffer data = ByteBuffer.allocateDirect(cue.pcm.length * 2).order(ByteOrder.nativeOrder());
		for (short sample : cue.pcm) {
			data.putShort(sample);
		}
		data.flip();
		return data;
	}

	private static void play(Cue cue, float volume) {
		if (!(volume > 0) || !GATE.allow(Util.getMillis())) {
			return;
		}
		SoundManager sounds = Minecraft.getInstance().getSoundManager();
		if (playing != null) {
			sounds.stop(playing);
		}
		Instance instance = new Instance(cue, Math.min(volume, 1.0F));
		playing = instance;
		sounds.play(instance);
	}

	private static Cue open() {
		if (openCue == null) {
			openCue = new Cue(OPEN, ToneSynth.open());
		}
		return openCue;
	}

	private static Cue close() {
		if (closeCue == null) {
			closeCue = new Cue(CLOSE, ToneSynth.close());
		}
		return closeCue;
	}

	/** One generated sound: its samples and the sound event that plays them. */
	private static final class Cue {
		final Sound sound;
		final WeighedSoundEvents event;
		final short[] pcm;

		Cue(Identifier id, short[] pcm) {
			// As vanilla's built-in empty sound: unit volume and pitch, weight 1, loaded whole rather than
			// streamed (streaming would go to the Ogg decoder), not preloaded.
			this.sound = new Sound(id, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, false, false, 16);
			// No subtitle: one would show "Flint UI" in the corner every time the menu opens.
			this.event = new WeighedSoundEvents(id, null);
			this.event.addSound(sound);
			this.pcm = pcm;
		}
	}

	/** An interface sound like vanilla's button click: UI category, heard at the listener, no distance falloff. */
	private static final class Instance extends AbstractSoundInstance {
		private final Cue cue;

		Instance(Cue cue, float volume) {
			super(cue.sound.getLocation(), SoundSource.UI, RANDOM);
			this.cue = cue;
			this.volume = volume;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.relative = true;
		}

		@Override
		public WeighedSoundEvents getOrResolve(SoundManager soundManager) {
			sound = cue.sound;
			soundEvent = cue.event;
			return cue.event;
		}
	}
}
