package com.flintclient.client.compat;

import net.minecraft.client.Minecraft;

/**
 * 1.21's frame timing ({@code net.minecraft.client.DeltaTracker}) on Minecraft 1.19.2, which hands its drawing code the
 * partial tick as a float: the game's own timer, read when asked ({@link #GAME}). 1.19.2 keeps one partial tick (paused, it
 * is the pause's), so the real-time and game-time answers are the same.
 */
public interface DeltaTracker {
	DeltaTracker GAME = new DeltaTracker() {
	};

	/** The game's timer now (what {@code Minecraft.getTimer()} answers on 1.21). */
	static DeltaTracker game() {
		return GAME;
	}

	default float getGameTimeDeltaPartialTick(boolean runsNormally) {
		return Minecraft.getInstance().getFrameTime();
	}

	default float getGameTimeDeltaTicks() {
		return Minecraft.getInstance().getDeltaFrameTime();
	}

	default float getRealtimeDeltaTicks() {
		return Minecraft.getInstance().getDeltaFrameTime();
	}
}
