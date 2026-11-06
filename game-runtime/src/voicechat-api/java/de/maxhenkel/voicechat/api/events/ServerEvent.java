// Compile-only stub of Simple Voice Chat's API (voicechat-api 2.6, by Max Henkel): only the signatures Flint calls. Never
// packaged; the real classes come from Simple Voice Chat when it is installed.
package de.maxhenkel.voicechat.api.events;

import de.maxhenkel.voicechat.api.VoicechatServerApi;

public interface ServerEvent extends Event {
	VoicechatServerApi getVoicechat();
}
