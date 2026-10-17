package com.flintclient.client.control.platform;

/** What Item Physics remembers on an item entity (by mixin): when it landed, and at which angle. Client side only. */
public interface DroppedItemMemory {
	/** The age (ticks) it landed at, or -1 while it's in the air. */
	float flint$groundSince();

	float flint$landAngle();

	void flint$landed(float since, float angle);
}
