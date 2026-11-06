package com.flintclient.client.control.platform.sound;

import com.flintclient.client.mixin.feature.SoundEngineAccessor;
import com.flintclient.client.mixin.feature.SoundManagerAccessor;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import org.jspecify.annotations.Nullable;

/** The sounds Minecraft is playing now and their channels (game thread). */
final class SoundAccess {
	private SoundAccess() {
	}

	static @Nullable Map<SoundInstance, ChannelAccess.ChannelHandle> playing(Minecraft mc) {
		Object engine = ((SoundManagerAccessor) mc.getSoundManager()).flint$soundEngine();
		return engine == null ? null : ((SoundEngineAccessor) engine).flint$instanceToChannel();
	}
}
