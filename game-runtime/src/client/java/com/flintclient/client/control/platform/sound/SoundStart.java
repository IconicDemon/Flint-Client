package com.flintclient.client.control.platform.sound;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

/**
 * Ambient Sounds' "start at a random point" (the mod's {@code playSoundWithOffset}): a looping sound begins somewhere in
 * its first three quarters, as the mod's random stream offset does, so a loop never starts the same way twice.
 */
public final class SoundStart {
	private SoundStart() {
	}

	/** A sound held whole in memory (sound thread, just before it plays): its play position moves to a random sample. */
	public static void randomOffset(int source) {
		if (AL10.alGetSourcei(source, AL11.AL_SOURCE_TYPE) != AL11.AL_STATIC) {
			return;
		}
		int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
		if (buffer == 0) {
			return;
		}
		int size = AL10.alGetBufferi(buffer, AL10.AL_SIZE);
		int bits = AL10.alGetBufferi(buffer, AL10.AL_BITS);
		int channels = AL10.alGetBufferi(buffer, AL10.AL_CHANNELS);
		if (size <= 0 || bits <= 0 || channels <= 0) {
			return;
		}
		int samples = (int) ((long) size * 8 / ((long) bits * channels));
		int limit = samples - samples / 4;
		if (limit > 0) {
			AL10.alSourcei(source, AL11.AL_SAMPLE_OFFSET, ThreadLocalRandom.current().nextInt(limit));
		}
	}

	/**
	 * A streamed sound: the stream with a random part of its first three quarters already read, for a stream of
	 * {@code totalBytes} decoded bytes (0: unknown, then it starts at the beginning).
	 */
	public static AudioStream skipping(AudioStream stream, long totalBytes) throws IOException {
		if (totalBytes <= 0) {
			return stream;
		}
		AudioFormat format = stream.getFormat();
		int frame = Math.max(1, format.getFrameSize());
		long limit = totalBytes - totalBytes / 4;
		long skip = limit <= frame ? 0 : ThreadLocalRandom.current().nextLong(limit / frame) * frame;
		while (skip > 0) {
			ByteBuffer chunk = stream.read((int) Math.min(skip, 1 << 16));
			int got = chunk.remaining();
			if (got <= 0) {
				break;
			}
			skip -= got;
		}
		return stream;
	}
}
