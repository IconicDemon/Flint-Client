package com.flintclient.client.perf;

import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

/**
 * One Flint Performance Core feature. {@link PerformanceCore} starts the enabled ones once at start-up
 * and calls them back on the render thread when the screen changes or a world is joined or left.
 */
public interface PerfModule {
	/** Stable id, also the key for overrides in the config file's {@code modules}. */
	String id();

	/**
	 * Whether the module runs when the config file doesn't override it. Optimizations return false when
	 * Automatic Optimization is off, so turning it off leaves the game as it would be without Flint's
	 * modules.
	 */
	boolean defaultEnabled(PerfConfig config, Compat compat);

	void start();

	/** {@code next} is null when the game returns to play with no screen open. */
	default void onScreenChanged(@Nullable Screen previous, @Nullable Screen next) {
	}

	default void onWorldJoined() {
	}

	default void onWorldLeft() {
	}
}
