package com.flintclient.client.control.platform.sound;

import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.ClientLocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.CreateOpenALContextEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.OpenALSoundEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;

/**
 * Sound Physics for Simple Voice Chat's voices, when that mod is installed next to Flint (it finds this plugin through
 * the "voicechat" entrypoint; without it, this class is never loaded). Each voice gets the same echo and muffling as a
 * game sound, worked out again at most every half second or when it moves a block; with "Hear yourself", your own voice
 * plays back as an echo of where you stand.
 *
 * <p>Adapted from Sound Physics Remastered's {@code SimpleVoiceChatPlugin} and {@code AudioChannel} (GPL-3.0; see
 * META-INF/licenses/GPL-3.0-SoundPhysicsRemastered.txt).
 */
public final class VoiceChatPlugin implements VoicechatPlugin {
	private static final UUID OWN_VOICE_ID = UUID.randomUUID();
	private static final String OWN_VOICE_CATEGORY = "own_voice";
	private final Map<UUID, Channel> channels = new HashMap<>();
	private ClientLocationalAudioChannel ownVoice;

	@Override
	public String getPluginId() {
		return "flintclient";
	}

	@Override
	public void initialize(VoicechatApi api) {
		com.flintclient.client.FlintClient.LOGGER.info("[Sound Physics - General] Initializing Simple Voice Chat integration");
		channels.clear();
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(CreateOpenALContextEvent.class, event -> com.flintclient.client.FlintClient.LOGGER.info(
			"[Sound Physics - General] Simple Voice Chat made its sound context; its effects are made with its first voice"));
		registration.registerEvent(OpenALSoundEvent.class, this::onSound);
		registration.registerEvent(ClientVoicechatConnectionEvent.class, this::onConnection);
		registration.registerEvent(ClientSoundEvent.class, this::onOwnSound);
		registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
			VolumeCategory category = event.getVoicechat().volumeCategoryBuilder().setId(OWN_VOICE_CATEGORY).setName("Own voice")
				.setDescription("The volume of your own voice").build();
			event.getVoicechat().registerVolumeCategory(category);
		});
	}

	private void onConnection(ClientVoicechatConnectionEvent event) {
		channels.values().removeIf(Channel::canBeRemoved);
		ownVoice = event.getVoicechat().createLocationalAudioChannel(OWN_VOICE_ID, event.getVoicechat().createPosition(0, 0, 0));
	}

	private void onOwnSound(ClientSoundEvent event) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (ownVoice == null || player == null || !SoundPhysics.voiceSettings(true)) {
			return;
		}
		Vec3 at = player.position();
		ownVoice.setCategory(OWN_VOICE_CATEGORY);
		ownVoice.setLocation(event.getVoicechat().createPosition(at.x, at.y, at.z));
		ownVoice.play(event.getRawAudio());
	}

	private void onSound(OpenALSoundEvent event) {
		if (!SoundPhysics.voiceSettings(false)) {
			return;
		}
		UUID id = event.getChannelId();
		if (id == null) {
			return;
		}
		int source = event.getSource();
		SoundPhysics.afterAttenuation(source, AL10.alGetSourcef(source, AL10.AL_MAX_DISTANCE));
		boolean auxOnly = SoundPhysics.voiceSettings(true) && OWN_VOICE_ID.equals(id);
		Position position = event.getPosition();
		channels.computeIfAbsent(id, Channel::new).onSound(source, position == null ? null : new Vec3(position.getX(), position.getY(), position.getZ()),
			directGainScale(event.getVoicechat(), auxOnly), event.getCategory());
	}

	private static float directGainScale(VoicechatClientApi api, boolean auxOnly) {
		if (auxOnly) {
			return 0;
		}
		return SoundPhysics.hasReferenceDistanceBug() && api.getClientConfig().getString("audio_type", "NORMAL").equals("NORMAL") ? 0.5F : 1;
	}

	/** One voice: worked out again every half second, or as soon as it moves a block. */
	private static final class Channel {
		private long lastUpdate;
		private Vec3 lastPos;

		Channel(UUID id) {
		}

		void onSound(int source, Vec3 at, float directGainScale, String category) {
			if (at == null) {
				SoundPhysics.voiceDefault(source, directGainScale);
				return;
			}
			long time = System.currentTimeMillis();
			if (time - lastUpdate >= 500 || lastPos == null || !(lastPos.distanceTo(at) < 1)) {
				SoundPhysics.voice(source, at, directGainScale, category);
				lastUpdate = time;
				lastPos = at;
			}
		}

		boolean canBeRemoved() {
			return System.currentTimeMillis() - lastUpdate > 5000;
		}
	}
}
