// Compile-only stub of Simple Voice Chat's API (voicechat-api 2.6, by Max Henkel): only the signatures Flint calls. Never
// packaged; the real classes come from Simple Voice Chat when it is installed.
package de.maxhenkel.voicechat.api;

import de.maxhenkel.voicechat.api.audiochannel.ClientLocationalAudioChannel;
import de.maxhenkel.voicechat.api.config.ConfigAccessor;
import java.util.UUID;

public interface VoicechatClientApi extends VoicechatApi {
	ClientLocationalAudioChannel createLocationalAudioChannel(UUID id, Position position);

	ConfigAccessor getClientConfig();
}
