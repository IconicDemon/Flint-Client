package com.flintclient.client;

/**
 * Minecraft 1.8.9's answer to the shared code's "is this a Minecraft Flint wasn't built for?" ({@code ui.UiFit}): no, this jar
 * only ever runs on 1.8.9 (its fabric.mod.json says so), so Flint's screens are always the ones built for it.
 */
public final class FlintPreLaunch {
	private FlintPreLaunch() {
	}

	public static boolean untested() {
		return false;
	}
}
