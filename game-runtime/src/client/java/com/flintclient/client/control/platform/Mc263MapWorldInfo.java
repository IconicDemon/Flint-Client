package com.flintclient.client.control.platform;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.jspecify.annotations.Nullable;

/**
 * What the maps know about the world beyond its chunks: the seed (singleplayer only: a server never sends it) and, from it,
 * which chunks slimes spawn in (Minecraft's own rule). Render thread.
 */
final class Mc263MapWorldInfo {
	private static final Map<Long, Boolean> SLIME = new HashMap<>();
	private static long slimeSeed;

	private Mc263MapWorldInfo() {
	}

	/** The world's seed in singleplayer, else null. */
	static @Nullable Long seed() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return null;
		}
		var overworld = server.overworld();
		return overworld == null ? null : overworld.getSeed();
	}

	/** Whether slimes spawn in chunk ({@code cx}, {@code cz}) of a world with {@code seed} (cached). */
	static boolean slime(long seed, int cx, int cz) {
		if (seed != slimeSeed) {
			SLIME.clear();
			slimeSeed = seed;
		}
		long key = (long) cx << 32 | cz & 0xFFFFFFFFL;
		Boolean known = SLIME.get(key);
		if (known == null) {
			if (SLIME.size() > 20_000) {
				SLIME.clear();
			}
			known = WorldgenRandom.seedSlimeChunk(cx, cz, seed, 987234911L).nextInt(10) == 0;
			SLIME.put(key, known);
		}
		return known;
	}
}
