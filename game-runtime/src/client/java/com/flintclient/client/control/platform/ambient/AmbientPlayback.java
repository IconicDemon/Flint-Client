package com.flintclient.client.control.platform.ambient;

import com.flintclient.client.control.platform.sound.SoundStart;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

/**
 * Ambient Sounds' random start for the sound engine's hooks: which sounds want it, and a streamed one's stream with a
 * random part of its first three quarters skipped (its length read once from the Ogg file's last page).
 */
public final class AmbientPlayback {
	private static final Map<Identifier, Long> LENGTHS = new ConcurrentHashMap<>();

	private AmbientPlayback() {
	}

	/** Whether {@code instance} is an Ambient Sounds loop that starts at a random point. */
	public static boolean randomOffset(SoundInstance instance) {
		return instance instanceof AmbientSound.Stream stream && stream.randomOffset;
	}

	/** {@code stream}, a random way into its first three quarters (on the I/O pool, before it reaches the sound thread). */
	public static CompletableFuture<AudioStream> skipped(CompletableFuture<AudioStream> stream, Identifier path) {
		return stream.thenApplyAsync(s -> {
			try {
				return SoundStart.skipping(s, decodedBytes(path));
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, Util.nonCriticalIoPool());
	}

	/** The decoded length of Ogg Vorbis file {@code path} in bytes (16-bit samples), or 0 when it can't be read. */
	static long decodedBytes(Identifier path) {
		return LENGTHS.computeIfAbsent(path, p -> {
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(p)) {
				byte[] data = in.readAllBytes();
				if (data.length < 44) {
					return 0L;
				}
				int channels = data[39] & 0xFF;
				long granule = -1;
				for (int i = data.length - 27; i >= 0; i--) {
					if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
						granule = 0;
						for (int b = 7; b >= 0; b--) {
							granule = granule << 8 | data[i + 6 + b] & 0xFF;
						}
						break;
					}
				}
				return granule > 0 && channels > 0 ? granule * channels * 2 : 0L;
			} catch (IOException | RuntimeException e) {
				return 0L;
			}
		});
	}
}
