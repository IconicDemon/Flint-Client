package com.flintclient.client.platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.instance.SimpleSoundInstance;
import net.minecraft.resource.Identifier;

/**
 * What Flint's screens ask of the game beyond drawing and input, on Minecraft 1.8.9: background work and the render thread,
 * the clipboard, the click sound, the Shift key. Platform class (see {@link Clock}).
 */
public final class Services {
	private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, task -> {
		Thread t = new Thread(task, "Flint worker");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY + 1);
		return t;
	});

	private Services() {
	}

	/** Runs {@code task} on Flint's background workers (1.8.9 has no shared pool for this). */
	public static void background(Runnable task) {
		WORKERS.execute(task);
	}

	/** Runs {@code task} on the render thread (from a worker or a network reply), at the game's next chance. */
	public static void onRenderThread(Runnable task) {
		Minecraft.getInstance().executeTask(task);
	}

	/** 1.8.9 has no reduced-motion signal (the menu's own Animations setting still applies). */
	public static boolean reducedMotion() {
		return false;
	}

	/** The click Minecraft's own buttons make. */
	public static void playClick() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.of(new Identifier("gui.button.press"), 1.0F));
	}

	/** The clipboard's text ("" when it holds none). */
	public static String clipboard() {
		return Screen.getClipboard();
	}

	public static void setClipboard(String text) {
		Screen.setClipboard(text);
	}

	/** {@code text} without what Minecraft doesn't allow in a text field (line breaks, control characters). */
	public static String filterText(String text) {
		return SharedConstants.stripInvalidChars(text);
	}

	/** A Shift key is held down right now. */
	public static boolean shiftDown() {
		return Screen.isShiftDown();
	}

	/** Whether {@code key} ({@link Keys}) is held down right now. */
	public static boolean keyDown(int key) {
		return org.lwjgl.input.Keyboard.isKeyDown(key);
	}
}
