package com.flintclient.client.control.feature;

/**
 * What Block Indicator calls a block made to pass for another: the block it passes for, so the HUD never tells more than
 * looking at it does (on a server, a trapped chest or an infested stone is a trap, and naming it would give it away). The same
 * built-in camouflage as Jade's (read from its 26.3 source, {@code JadeClient.builtInOverrides}): a trapped chest is a chest,
 * an infested block is its host block, powder snow is a snow block unless you were just in it. Creative and spectator players
 * see what the block is, as there. Pure, for tests; the game looks the disguise up in the block's own namespace and keeps the
 * real block when there is no such block.
 */
public final class BlockCamouflage {
	/** How long after you were in powder snow it is named as itself (Jade's 8 seconds). */
	public static final long POWDER_SNOW_KNOWN_MILLIS = 8_000;

	private static final String TRAPPED = "trapped_";
	private static final String TRAPPED_CHEST = "_trapped_chest";
	private static final String INFESTED = "infested_";

	private BlockCamouflage() {
	}

	/**
	 * The registry path of the block {@code path} passes for ({@code trapped_chest} → {@code chest}, {@code infested_stone_bricks}
	 * → {@code stone_bricks}, {@code powder_snow} → {@code snow_block}); {@code path} itself for every other block, and when
	 * {@code seesThrough} (creative or spectator) or {@code knowsPowderSnow} (in powder snow within the last
	 * {@link #POWDER_SNOW_KNOWN_MILLIS}) for powder snow.
	 */
	public static String disguise(String path, boolean seesThrough, boolean knowsPowderSnow) {
		if (seesThrough) {
			return path;
		}
		if (path.equals("powder_snow")) {
			return knowsPowderSnow ? path : "snow_block";
		}
		if (path.endsWith(TRAPPED_CHEST) && path.length() > TRAPPED_CHEST.length()) {
			return path.substring(0, path.length() - TRAPPED_CHEST.length()) + "_chest";
		}
		if (path.startsWith(TRAPPED) && path.length() > TRAPPED.length()) {
			return path.substring(TRAPPED.length());
		}
		if (path.startsWith(INFESTED) && path.length() > INFESTED.length()) {
			return path.substring(INFESTED.length());
		}
		return path;
	}

	/** Whether you count as knowing powder snow for what it is: you were in it {@code sinceMillis} ago (negative: never). */
	public static boolean knowsPowderSnow(long sinceMillis) {
		return sinceMillis >= 0 && sinceMillis <= POWDER_SNOW_KNOWN_MILLIS;
	}
}
