package com.flintclient.client.compat.data;

import com.flintclient.client.control.platform.Mc263BetterF3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * The F3 screen's charts on Minecraft 1.19.2, for Custom F3 ({@link Mc263BetterF3}; the module is unavailable on 1.19.x, so this
 * keeps its calls whole): 1.19.2 keeps them as switches in the options, drawn while F3 is open, the profiler pie
 * ({@code renderDebugCharts}, F3 + Shift) and the FPS and TPS graphs ({@code renderFpsChart}, F3 + Alt). It has no network
 * charts (1.20.2 added them). The toggles work as 1.20.2's: a chart turned on opens the F3 screen with it.
 */
public final class DebugCharts implements Mc263BetterF3.Charts {
	public static final DebugCharts INSTANCE = new DebugCharts();

	private DebugCharts() {
	}

	private static Options options() {
		return Minecraft.getInstance().options;
	}

	@Override
	public boolean flint$fpsCharts() {
		return options().renderFpsChart;
	}

	@Override
	public void flint$showCharts(boolean profiler, boolean fps) {
		Options options = options();
		options.renderDebugCharts |= profiler;
		options.renderFpsChart |= fps;
	}

	/** 1.19.2 has no network charts. */
	@Override
	public void flint$toggleNetworkCharts() {
	}

	/** Whether the profiler pie shows (the F3 screen showing, with the HUD). */
	public static boolean showProfilerChart() {
		Options options = options();
		return options.renderDebug && !options.hideGui && options.renderDebugCharts;
	}

	public static boolean showNetworkCharts() {
		return false;
	}

	public static void toggleProfilerChart() {
		Options options = options();
		options.renderDebugCharts = !options.renderDebug || !options.renderDebugCharts;
		if (options.renderDebugCharts) {
			options.renderDebug = true;
		}
	}

	public static void toggleFpsCharts() {
		Options options = options();
		options.renderFpsChart = !options.renderDebug || !options.renderFpsChart;
		if (options.renderFpsChart) {
			options.renderDebug = true;
		}
	}
}
