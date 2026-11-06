package com.flintclient.client.control.platform.ambient;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * A biome name or (with "#") tag pattern: "*" matches anything, and the name may appear anywhere in the id. Results are
 * remembered per pattern. Adapted from AmbientSounds (LGPL-3.0, CreativeMD).
 */
record BiomeCondition(boolean tag, Pattern pattern, Map<Identifier, Boolean> cache) {
	static BiomeCondition of(String name) {
		return name.startsWith("#") ? new BiomeCondition(true, Pattern.compile(".*" + name.substring(1).replace("*", ".*") + ".*"), new HashMap<>())
			: new BiomeCondition(false, Pattern.compile(".*" + name.replace("*", ".*") + ".*"), new HashMap<>());
	}

	static BiomeCondition @Nullable [] of(String @Nullable [] names) {
		if (names == null) {
			return null;
		}
		BiomeCondition[] compiled = new BiomeCondition[names.length];
		for (int i = 0; i < names.length; i++) {
			compiled[i] = of(names[i]);
		}
		return compiled;
	}

	/** Game thread only (the engine's slow tick). */
	boolean matches(Identifier location) {
		Boolean result = cache.get(location);
		if (result == null) {
			result = pattern.matcher(location.toString()).matches();
			if (cache.size() < 256) {
				cache.put(location, result);
			}
		}
		return result;
	}
}
