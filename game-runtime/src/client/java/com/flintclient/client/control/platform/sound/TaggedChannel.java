package com.flintclient.client.control.platform.sound;

import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

/**
 * What Flint keeps on each of Minecraft's sound channels (added by {@code mixin.feature.SoundChannelMixin}): its OpenAL
 * source, and which sound and category it plays (set on the sound thread, just before the channel is set up).
 */
public interface TaggedChannel {
	int flint$source();

	void flint$tag(Identifier sound, SoundSource category, boolean randomOffset);

	@Nullable Identifier flint$sound();

	@Nullable SoundSource flint$category();

	boolean flint$randomOffset();
}
