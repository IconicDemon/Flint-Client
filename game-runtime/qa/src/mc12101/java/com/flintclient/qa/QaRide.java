package com.flintclient.qa;

import net.minecraft.world.entity.Entity;

/** QaRide on 1.21.1: its startRiding has no event switch. Dev-only. */
final class QaRide {
	private QaRide() {
	}

	static boolean ride(Entity passenger, Entity vehicle) {
		return passenger.startRiding(vehicle, true);
	}
}
