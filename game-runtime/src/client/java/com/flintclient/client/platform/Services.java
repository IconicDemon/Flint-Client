package com.flintclient.client.platform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Util;

/**
 * What Flint's screens ask of the game beyond drawing and input: background work and the render thread, the clipboard,
 * the click sound, the reduced-motion signal, the Shift key. Platform class (see {@link Clock}).
 */
public final class Services {
	private Services() {
	}

	/** Runs {@code task} on the game's background workers. */
	public static void background(Runnable task) {
		Util.backgroundExecutor().execute(task);
	}

	/** Runs {@code task} on the render thread (from a worker or a network reply), at the game's next chance. */
	public static void onRenderThread(Runnable task) {
		Minecraft.getInstance().execute(task);
	}

	/**
	 * Minecraft has no "reduce motion" setting; a panorama speed of 0 (Accessibility settings) is the closest signal, so
	 * Flint then switches transitions off and shows everything immediately.
	 */
	public static boolean reducedMotion() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.options != null && minecraft.options.panoramaSpeed().get() <= 0.0;
	}

	/** The click Minecraft's own buttons make. */
	public static void playClick() {
		AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
	}

	/** The clipboard's text ("" when it holds none). */
	public static String clipboard() {
		return Minecraft.getInstance().keyboardHandler.getClipboard();
	}

	public static void setClipboard(String text) {
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
	}

	/** {@code text} without what Minecraft doesn't allow in a text field (line breaks, control characters). */
	public static String filterText(String text) {
		return StringUtil.filterText(text);
	}

	/** A Shift key is held down right now. */
	public static boolean shiftDown() {
		return Minecraft.getInstance().hasShiftDown();
	}

	/** Whether {@code key} ({@link Keys}) is held down right now. */
	public static boolean keyDown(int key) {
		return com.flintclient.client.control.platform.Mc263ControlPlatform.keyDown(key);
	}
}
