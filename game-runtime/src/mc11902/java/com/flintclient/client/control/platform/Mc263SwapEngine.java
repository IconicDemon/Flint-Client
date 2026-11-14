package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.mixin.swap.SwapWindowAccess;
import com.flintclient.client.module.social.InstantSwap;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Instant Swap on Minecraft 1.19.2 and 1.19 ({@link InstantSwap}): the pause menu's own Save and Quit or Disconnect, its
 * window through GLFW (1.19's windowing), and joining a world by its folder or a server by its address as Minecraft's own
 * screens do. The same engine as 26.3's, on 1.19's calls. Found by name from {@link InstantSwap}. Render thread.
 */
public final class Mc263SwapEngine implements InstantSwap.Engine {
	/**
	 * The old game's GUI scale, from its handover. Minecraft refuses a scale that doesn't fit the window (a standby's hidden
	 * 854x480 window fits 2, so 3 loaded as the default), so it is set again once the window has its real size.
	 */
	private int wantedGuiScale = -1;

	@Override
	public boolean atMainMenu() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getOverlay() == null && mc.screen != null && mc.level == null;
	}

	@Override
	public boolean inWorldReady() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && mc.screen == null && mc.getOverlay() == null && mc.levelRenderer.hasRenderedAllChunks();
	}

	@Override
	public Path gameDir() {
		return Minecraft.getInstance().gameDirectory.toPath();
	}

	@Override
	public void saveOptions() {
		Minecraft.getInstance().options.save();
	}

	@Override
	public boolean reloadOptions(Runnable then) {
		// The old game's keys, GUI scale, video and sound settings, as it saved them a moment ago. The GUI scale is set again
		// once the window has the old one's size ({@link #settle}).
		Minecraft mc = Minecraft.getInstance();
		java.util.List<String> packsBefore = new java.util.ArrayList<>(mc.options.resourcePacks);
		mc.options.load();
		if (packsBefore.equals(mc.options.resourcePacks)) {
			return false;
		}
		// The player turned resource packs on or off after this game started: it loads them now, still hidden, then joins.
		mc.options.loadSelectedResourcePacks(mc.getResourcePackRepository());
		mc.reloadResourcePacks().thenRun(() -> mc.execute(then));
		return true;
	}

	@Override
	public void settle() {
		if (topmostSettles > 0 && --topmostSettles == 0) {
			topmost(Minecraft.getInstance().getWindow().getWindow(), false);
		}
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		int[] fw = new int[1];
		int[] fh = new int[1];
		GLFW.glfwGetFramebufferSize(w.getWindow(), fw, fh);
		if (fw[0] != w.getWidth() || fh[0] != w.getHeight()) {
			// Minecraft hadn't heard of the new size yet (GLFW tells it by an event, later): it reads it now.
			((SwapWindowAccess) (Object) w).flint$refreshFramebufferSize();
			mc.resizeDisplay();
			FlintClient.LOGGER.info("Flint: Instant Swap: window {}x{}", w.getWidth(), w.getHeight());
		}
		if (wantedGuiScale >= 0) {
			// Valid now the window is its real size (set() refuses a scale the window can't fit).
			if (mc.options.guiScale().get() != wantedGuiScale) {
				mc.options.guiScale().set(wantedGuiScale);
			}
			if (mc.options.guiScale().get() == wantedGuiScale) {
				FlintClient.LOGGER.info("Flint: Instant Swap: GUI scale {} as in the old game", wantedGuiScale);
				wantedGuiScale = -1;
			}
		}
		int wanted = w.calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
		if ((int) w.getGuiScale() != wanted) {
			mc.resizeDisplay();
			FlintClient.LOGGER.info("Flint: Instant Swap: GUI scale {} (options {})", (int) w.getGuiScale(), mc.options.guiScale().get());
		}
	}

	@Override
	public boolean fitted() {
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		return wantedGuiScale < 0 && (int) w.getGuiScale() == w.calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
	}

	@Override
	public String fit() {
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		return w.getWidth() + "x" + w.getHeight() + " GUI scale " + (int) w.getGuiScale() + " (options " + mc.options.guiScale().get() + ")";
	}

	@Override
	public java.util.concurrent.CompletableFuture<Boolean> loadLive(Path jar) {
		return com.flintclient.client.hotload.HotLoader.load(jar).thenApply(r -> {
			FlintClient.LOGGER.info("Flint: {} {}", jar.getFileName(), r.loaded() ? "loaded live (" + r.renderThreadMs() + " ms on the render thread)"
				: "can't load live (" + r.reason() + "): Instant Swap brings it in");
			return r.loaded();
		});
	}

	@Override
	public boolean promoted() {
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return true;
		}
		try {
			com.sun.jna.NativeLibrary kernel = com.sun.jna.NativeLibrary.getInstance("kernel32");
			com.sun.jna.Pointer self = kernel.getFunction("GetCurrentProcess").invokePointer(new Object[0]);
			int priority = kernel.getFunction("GetPriorityClass").invokeInt(new Object[] {self});
			// NORMAL, ABOVE_NORMAL (0x8000) or HIGH (0x80): not the standby's below normal (0x4000) or idle (0x40).
			return priority == NORMAL || priority == 0x8000 || priority == 0x80;
		} catch (RuntimeException | LinkageError e) {
			return true;
		}
	}

	@Override
	public void closeScreens() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null && mc.screen != null) {
			mc.setScreen(null);
		}
	}

	@Override
	public void allowForeground() {
		// The game in front lets the new one take the front (and the keyboard) when it shows itself: Windows only lets the
		// foreground process hand that on.
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return;
		}
		try {
			com.sun.jna.NativeLibrary user = com.sun.jna.NativeLibrary.getInstance("user32");
			user.getFunction("AllowSetForegroundWindow").invokeInt(new Object[] {-1});
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.debug("Flint: Instant Swap: couldn't hand the front on", e);
		}
	}

	@Override
	public @Nullable String world() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return null;
		}
		Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		Path name = root.getFileName();
		return name == null ? null : name.toString();
	}

	@Override
	public @Nullable String server() {
		Minecraft mc = Minecraft.getInstance();
		ServerData data = mc.getCurrentServer();
		return data == null || mc.isConnectedToRealms() || mc.hasSingleplayerServer() ? null : data.ip;
	}

	@Override
	public JsonObject window() {
		Minecraft mc = Minecraft.getInstance();
		long handle = mc.getWindow().getWindow();
		JsonObject out = new JsonObject();
		int[] a = new int[1];
		int[] b = new int[1];
		GLFW.glfwGetWindowPos(handle, a, b);
		out.addProperty("x", a[0]);
		out.addProperty("y", b[0]);
		GLFW.glfwGetWindowSize(handle, a, b);
		out.addProperty("width", Math.max(320, a[0]));
		out.addProperty("height", Math.max(240, b[0]));
		out.addProperty("maximized", GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) != 0);
		out.addProperty("fullscreen", mc.options.fullscreen().get());
		out.addProperty("guiScale", mc.options.guiScale().get());
		// The launcher puts its still over this process's window exactly (physical pixels).
		out.addProperty("pid", ProcessHandle.current().pid());
		return out;
	}

	@Override
	public void leave() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			// What the pause menu's Save and Quit to Title (or Disconnect) runs: the world is saved and closed when it returns.
			boolean local = mc.isLocalServer();
			mc.level.disconnect();
			if (local) {
				mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
			} else {
				mc.clearLevel();
			}
			mc.setScreen(new TitleScreen());
		}
	}

	@Override
	public void quit() {
		Minecraft.getInstance().stop();
	}

	@Override
	public void show(@Nullable JsonObject window) {
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		long handle = w.getWindow();
		if (window != null && window.has("guiScale")) {
			wantedGuiScale = window.get("guiScale").getAsInt();
		}
		// Still hidden: the old window's size, place and mode, and the player's GUI scale, so the first frame the player can
		// ever see is at its final size and scale (reveal() shows it, under the launcher's still, a few frames later).
		maximizeOnReveal = false;
		try {
			if (window != null) {
				maximizeOnReveal = window.has("maximized") && window.get("maximized").getAsBoolean();
				if (window.has("width")) {
					GLFW.glfwSetWindowSize(handle, window.get("width").getAsInt(), window.get("height").getAsInt());
				}
				if (window.has("x")) {
					GLFW.glfwSetWindowPos(handle, window.get("x").getAsInt(), window.get("y").getAsInt());
				}
				if (window.has("fullscreen") && window.get("fullscreen").getAsBoolean()) {
					// Started windowed while hidden (SwapWindowMixin); the player's fullscreen comes back now.
					if (!w.isFullscreen()) {
						w.toggleFullScreen();
					}
					mc.options.fullscreen().set(true);
				}
			}
			GLFW.glfwPollEvents();
			settle();
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: Instant Swap: couldn't place the window; it shows as it is", e);
		}
	}

	private boolean maximizeOnReveal;

	@Override
	public void reveal() {
		long handle = Minecraft.getInstance().getWindow().getWindow();
		// Shown CLOAKED (Windows' compositor keeps it off the screen while the game draws into it), so the first picture of it
		// anyone can ever see is a frame the game drew, never the empty (white) window. Not raised: once uncloaked it sits under
		// the launcher's still (always on top), which goes once this window has drawn on screen.
		cloak(handle, true);
		GLFW.glfwShowWindow(handle);
		if (maximizeOnReveal) {
			GLFW.glfwMaximizeWindow(handle);
		}
		GLFW.glfwPollEvents();
		settle();
	}

	@Override
	public void uncloak() {
		cloak(Minecraft.getInstance().getWindow().getWindow(), false);
	}

	/** DWMWA_CLOAK on the window (Windows); nothing elsewhere. */
	private static void cloak(long handle, boolean on) {
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return;
		}
		try {
			long hwnd = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle);
			if (hwnd == 0L) {
				return;
			}
			com.sun.jna.Memory value = new com.sun.jna.Memory(4);
			value.setInt(0, on ? 1 : 0);
			int result = com.sun.jna.NativeLibrary.getInstance("dwmapi").getFunction("DwmSetWindowAttribute")
				.invokeInt(new Object[] {new com.sun.jna.Pointer(hwnd), 13 /* DWMWA_CLOAK */, value, 4});
			FlintClient.LOGGER.info("Flint: Instant Swap: window {} ({})", on ? "cloaked" : "uncloaked", result == 0 ? "ok" : "0x" + Integer.toHexString(result));
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.debug("Flint: Instant Swap: no cloak", e);
		}
	}

	@Override
	public void preSave() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			long start = System.currentTimeMillis();
			server.saveEverything(true, false, false);
			FlintClient.LOGGER.info("Flint: Instant Swap: world saved ahead in {} ms (while still playing)", System.currentTimeMillis() - start);
		});
	}

	@Override
	public boolean levelLoaded() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null;
	}

	private static com.sun.jna.@Nullable Function dwmFlush;
	private static boolean dwmLooked;

	/**
	 * While coming in (Windows): waits until the compositor has composed the frame just presented (DwmFlush), so a frame counted
	 * before the still goes is one Windows really has on screen for this window, never an empty (white) surface it hasn't
	 * composed yet (as 1.21.x's). A few frames per swap; nothing otherwise.
	 */
	public static void waitComposed() {
		if (!dwmLooked) {
			dwmLooked = true;
			if (System.getProperty("os.name", "").startsWith("Windows")) {
				try {
					dwmFlush = com.sun.jna.NativeLibrary.getInstance("dwmapi").getFunction("DwmFlush");
				} catch (RuntimeException | LinkageError e) {
					FlintClient.LOGGER.debug("Flint: Instant Swap: no DwmFlush", e);
				}
			}
		}
		com.sun.jna.Function f = dwmFlush;
		if (f != null) {
			try {
				f.invokeInt(new Object[0]);
			} catch (RuntimeException | LinkageError e) {
				dwmFlush = null;
			}
		}
	}

	@Override
	public void hide() {
		GLFW.glfwHideWindow(Minecraft.getInstance().getWindow().getWindow());
	}

	@Override
	public boolean drawsWorld() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && mc.getOverlay() == null;
	}

	// keepActive: 1.19 has no inactivity frame limit (1.21.2 added it), so a standby coming in is never throttled: nothing to do.

	@Override
	public void front() {
		long handle = Minecraft.getInstance().getWindow().getWindow();
		// Above the launcher's still (which is always on top) for its last frames, so the frames counted before the still goes
		// are composed on screen for real: under a still that covers it, the window's surface may never be composed, and the
		// first picture once the still went was its empty (white) surface. Back to a plain window a moment later (settle).
		topmost(handle, true);
		topmostSettles = TOPMOST_SETTLES;
		GLFW.glfwFocusWindow(handle);
		settle();
	}

	/** settle() calls (once a tick after the window came in front) before the window stops being always on top. */
	private static final int TOPMOST_SETTLES = 50;
	private int topmostSettles;

	/** HWND_TOPMOST / HWND_NOTOPMOST for the window (Windows), without moving, sizing or activating it. */
	private static void topmost(long handle, boolean on) {
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return;
		}
		try {
			long hwnd = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle);
			if (hwnd == 0L) {
				return;
			}
			int done = com.sun.jna.NativeLibrary.getInstance("user32").getFunction("SetWindowPos").invokeInt(new Object[] {
				new com.sun.jna.Pointer(hwnd), new com.sun.jna.Pointer(on ? -1L : -2L), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010});
			FlintClient.LOGGER.info("Flint: Instant Swap: window {} ({})", on ? "above the still" : "a plain window again", done != 0 ? "ok" : "refused");
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.debug("Flint: Instant Swap: no topmost", e);
		}
	}

	@Override
	public void join(@Nullable String world, @Nullable String server) {
		Minecraft mc = Minecraft.getInstance();
		if (world != null) {
			mc.createWorldOpenFlows().loadLevel(new TitleScreen(), world);
		} else if (server != null) {
			ServerData data = new ServerData(server, server, false);
			ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server), data);
		}
	}

	@Override
	public void notice(String text) {
		Mc263NoticeToast.show(text);
	}

	@Override
	public void captureFrame(Path file, Runnable done) {
		// 1.19's screenshot reads the main target at once (26.3's hands the picture over later).
		try (NativeImage image = net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
			image.writeToFile(file);
		} catch (java.io.IOException | RuntimeException e) {
			FlintClient.LOGGER.debug("Flint: Instant Swap: couldn't write the still frame", e);
		} finally {
			done.run();
		}
	}

	/**
	 * BELOW_NORMAL_PRIORITY_CLASS / NORMAL_PRIORITY_CLASS (Windows). Never PROCESS_MODE_BACKGROUND: its very low disk and memory
	 * priority starved a standby's loading for minutes.
	 */
	private static final int BELOW_NORMAL = 0x00004000;
	private static final int NORMAL = 0x00000020;

	@Override
	public void background(boolean on) {
		if (!System.getProperty("os.name", "").startsWith("Windows")) {
			return;
		}
		try {
			com.sun.jna.NativeLibrary kernel = com.sun.jna.NativeLibrary.getInstance("kernel32");
			com.sun.jna.Pointer self = kernel.getFunction("GetCurrentProcess").invokePointer(new Object[0]);
			int done = kernel.getFunction("SetPriorityClass").invokeInt(new Object[] {self, on ? BELOW_NORMAL : NORMAL});
			FlintClient.LOGGER.info("Flint: Instant Swap: priority {} ({})", on ? "below normal" : "normal", done != 0 ? "ok" : "refused");
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.debug("Flint: Instant Swap: no background mode", e);
		}
	}
}
