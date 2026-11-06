// Compile-only stub of Simple Voice Chat's API (voicechat-api 2.6, by Max Henkel): only the signatures Flint calls. Never
// packaged; the real classes come from Simple Voice Chat when it is installed.
package de.maxhenkel.voicechat.api.config;

public interface ConfigAccessor {
	String getValue(String key);

	default String getString(String key, String fallback) {
		String value = getValue(key);
		return value == null ? fallback : value;
	}
}
