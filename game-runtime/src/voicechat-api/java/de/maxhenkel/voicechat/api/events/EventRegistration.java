// Compile-only stub of Simple Voice Chat's API (voicechat-api 2.6, by Max Henkel): only the signatures Flint calls. Never
// packaged; the real classes come from Simple Voice Chat when it is installed.
package de.maxhenkel.voicechat.api.events;

import java.util.function.Consumer;

public interface EventRegistration {
	<T extends Event> void registerEvent(Class<T> type, Consumer<T> listener, int priority);

	default <T extends Event> void registerEvent(Class<T> type, Consumer<T> listener) {
		registerEvent(type, listener, 0);
	}
}
