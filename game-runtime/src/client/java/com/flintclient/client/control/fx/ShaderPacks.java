package com.flintclient.client.control.fx;

import java.lang.reflect.Method;

/**
 * Whether Iris is drawing the world with a shader pack (as Natural Motion Blur's IrisCompat asks it: Iris's public API, by
 * reflection, so Flint needs no Iris at build time and runs the same without it). A shader pack draws the world through its own
 * passes, so Motion Blur then leaves Minecraft's terrain pass alone and blurs the finished world once, after it (see
 * {@link NmbTiming#lateMode}). Asked once a client tick ({@link #refresh}), never per frame; any failure reads as no pack.
 */
public final class ShaderPacks {
	private static final Object[] NO_ARGS = new Object[0];
	private static boolean looked;
	private static Method getInstance;
	private static Method inUse;
	private static boolean active;

	private ShaderPacks() {
	}

	/** Whether a shader pack was in use at the last {@link #refresh}. */
	public static boolean active() {
		return active;
	}

	/** Asks Iris again (from the client tick). */
	public static void refresh() {
		if (!looked) {
			looked = true;
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				getInstance = api.getMethod("getInstance");
				inUse = api.getMethod("isShaderPackInUse");
			} catch (Throwable absent) {
				getInstance = null;
				inUse = null;
			}
		}
		if (getInstance == null || inUse == null) {
			active = false;
			return;
		}
		try {
			active = Boolean.TRUE.equals(inUse.invoke(getInstance.invoke(null, NO_ARGS), NO_ARGS));
		} catch (Throwable e) {
			active = false;
		}
	}
}
