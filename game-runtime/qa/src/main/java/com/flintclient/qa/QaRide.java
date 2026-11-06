package com.flintclient.qa;

import net.minecraft.world.entity.Entity;

/**
 * {@code passenger} rides {@code vehicle} on the client, as the client does when a server's passengers packet says so (a
 * Paper server hangs a text display on a player that way; Minecraft's own server refuses players as vehicles). 1.21.1 has the
 * two-argument form (src/mc12101). Dev-only.
 */
final class QaRide {
	private QaRide() {
	}

	static boolean ride(Entity passenger, Entity vehicle) {
		return passenger.startRiding(vehicle, true, false);
	}
}
