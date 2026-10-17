package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.SoundMixer;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.BetterSounds;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;

/**
 * Minecraft 26.3's side of Better Sounds: the factor the sound engine's volume is multiplied by, per sound. Factors are
 * cached per sound id and dropped whenever the module's settings (or the profile) change, so a sound costs one map read.
 * Called on the render thread (the sound engine's volume calculations); fault-isolated.
 */
public final class Mc263Sounds {
	private static final int MODULE = Mods.index(BetterSounds.ID);
	private static final HookGuard GUARD = new HookGuard("better sounds",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final Map<Identifier, Float> CACHE = new HashMap<>();
	private static final int[] CATEGORIES = new int[SoundMixer.CATEGORIES.length + 1];
	private static ModuleState cachedFor;
	private static int cachedRevision = -1;

	private Mc263Sounds() {
	}

	/** The factor (0 to 1) for {@code instance}; 1 while Better Sounds is off. */
	public static float factor(SoundInstance instance) {
		if (!GUARD.on() || !Mods.on(MODULE)) {
			return 1;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			if (state != cachedFor || state.revision() != cachedRevision) {
				cachedFor = state;
				cachedRevision = state.revision();
				CACHE.clear();
				for (int i = 0; i < CATEGORIES.length; i++) {
					CATEGORIES[i] = state.integer(BetterSounds.CATEGORY[i]);
				}
			}
			Identifier id = instance.getIdentifier();
			Float cached = CACHE.get(id);
			if (cached == null) {
				cached = SoundMixer.factor(id.toString(), CATEGORIES, state.values(BetterSounds.PER_SOUND));
				CACHE.put(id, cached);
			}
			return cached;
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return 1;
		}
	}
}
