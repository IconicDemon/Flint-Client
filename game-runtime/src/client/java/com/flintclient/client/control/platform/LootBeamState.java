package com.flintclient.client.control.platform;

import org.jspecify.annotations.Nullable;

/** Room on a dropped item's render state for what Loot Beams draws over it this frame ({@link Mc263LootBeams}). */
public interface LootBeamState {
	Mc263LootBeams.@Nullable Draw flint$loot();

	void flint$loot(Mc263LootBeams.@Nullable Draw draw);
}
