/*
 * Screenshot to Clipboard, after ClipboardScreenshot by Blugon0921 (https://github.com/blugon0921/ClipboardScreenshot).
 * MIT License: see META-INF/licenses/MIT-ClipboardScreenshot.txt.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.UtilityMods.Screenshots;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * The Screenshot card's clipboard key, as ClipboardScreenshot: a press (in a world or a menu) grabs what is on screen and puts
 * it on the system clipboard as an image, then says so in chat, on the action bar or in a popup (chat and the action bar
 * fall back to a popup without a world). The image is converted and copied off the render thread; Windows and Linux use
 * Java's clipboard (the game starts with it switched off, so it is switched on before its first use), macOS its own
 * {@code osascript}, which needs no window toolkit inside the game. Fault-isolated.
 */
public final class Mc263ScreenshotClipboard {
	private static final int SCREENSHOTS = Mods.index(Screenshots.ID);
	private static final HookGuard GUARD = new HookGuard("screenshot clipboard",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Flint screenshot clipboard");
		thread.setDaemon(true);
		return thread;
	});
	private static boolean held;

	private Mc263ScreenshotClipboard() {
	}

	/** Each client tick: a fresh press of the key copies the screen. */
	public static void tick() {
		if (!GUARD.on() || !Mods.on(SCREENSHOTS)) {
			held = false;
			return;
		}
		try {
			boolean down = Mc263Features.keyHeld(Mods.state(SCREENSHOTS).text(Screenshots.COPY_KEY));
			if (down && !held) {
				copy();
			}
			held = down;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void copy() {
		Minecraft mc = Minecraft.getInstance();
		if (!available()) {
			notify(mc, Component.literal("Clipboard not supported in this environment").withStyle(ChatFormatting.RED));
			return;
		}
		Screenshot.takeScreenshot(com.flintclient.client.compat.McCompat.mainRenderTarget(), image -> WORKER.execute(() -> {
			boolean copied;
			try {
				copied = MAC ? copyMac(image) : copyAwt(toBufferedImage(image));
			} catch (Exception | LinkageError e) {
				FlintClient.LOGGER.warn("Flint: couldn't copy the screenshot to the clipboard", e);
				copied = false;
			} finally {
				image.close();
			}
			boolean done = copied;
			mc.execute(() -> notify(mc, done ? Component.literal("Screenshot copied to clipboard")
				: Component.literal("Clipboard not supported in this environment").withStyle(ChatFormatting.RED)));
		}));
	}

	/** Whether there is a clipboard to copy to: macOS's own, or Java's once it may use the window system. */
	private static boolean available() {
		if (MAC) {
			return true;
		}
		try {
			System.setProperty("java.awt.headless", "false");
			return !GraphicsEnvironment.isHeadless();
		} catch (LinkageError | RuntimeException e) {
			return false;
		}
	}

	/** The screenshot's pixels as Java's image (Minecraft's are ARGB too). */
	private static BufferedImage toBufferedImage(NativeImage image) {
		BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < image.getWidth(); x++) {
			for (int y = 0; y < image.getHeight(); y++) {
				out.setRGB(x, y, image.getPixel(x, y));
			}
		}
		return out;
	}

	private static boolean copyAwt(BufferedImage image) {
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new Transferable() {
			@Override
			public DataFlavor[] getTransferDataFlavors() {
				return new DataFlavor[] {DataFlavor.imageFlavor};
			}

			@Override
			public boolean isDataFlavorSupported(DataFlavor flavor) {
				return flavor == DataFlavor.imageFlavor;
			}

			@Override
			public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
				if (!isDataFlavorSupported(flavor)) {
					throw new UnsupportedFlavorException(flavor);
				}
				return image;
			}
		}, null);
		return true;
	}

	/** macOS: the PNG written to a temporary file and read onto the clipboard by AppleScript. */
	private static boolean copyMac(NativeImage image) throws Exception {
		Path file = Files.createTempFile("flint-screenshot-", ".png");
		try {
			image.writeToFile(file);
			Process process = new ProcessBuilder("osascript", "-e",
				"set the clipboard to (read (POSIX file \"" + file.toAbsolutePath() + "\") as «class PNGf»)").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
		} finally {
			Files.deleteIfExists(file);
		}
	}

	/** Says so as the Notification option asks: chat, the action bar, a popup or nothing (a popup without a world). */
	private static void notify(Minecraft mc, Component text) {
		ModuleState s = Mods.state(SCREENSHOTS);
		int type = s.option(Screenshots.NOTIFICATION);
		if (type == 3) {
			return;
		}
		if (type == 2 || mc.player == null) {
			SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.NARRATOR_TOGGLE, text, Component.literal(""));
		} else if (type == 0) {
			com.flintclient.client.compat.McCompat.hud().getChat().addClientSystemMessage(text);
		} else {
			com.flintclient.client.compat.McCompat.hud().setOverlayMessage(text, false);
		}
	}
}
