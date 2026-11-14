package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.mixin.swap.SwapWindowAccess;
import com.flintclient.client.module.social.InstantSwap;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import java.nio.IntBuffer;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/**
 * Instant Swap on Minecraft 26.1 ({@link InstantSwap}; 26.3's engine with GLFW for SDL): Minecraft's own Save and
 * Quit or Disconnect, its window through GLFW (this line's windowing), and joining a world by its folder or a server by its
 * address as Minecraft's own screens do. Found by name from {@link InstantSwap}: lines without this class have no Instant
 * Swap. Render thread.
 */
public final class Mc263SwapEngine implements InstantSwap.Engine {
	/**
	 * The old game's GUI scale, from its handover. Minecraft clamps the scale it loads from options.txt to what fits the window
	 * then (a standby's hidden 854x480 window fits 2, so 3 loaded as 2), so it is set again once the window has its real size.
	 */
	private int wantedGuiScale = -1;

	@Override
	public boolean atMainMenu() {
		Minecraft mc = Minecraft.getInstance();
		return mc.gui.overlay() == null && mc.gui.screen() != null && mc.level == null;
	}

	@Override
	public boolean inWorldReady() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null
			&& mc.levelRenderer.hasRenderedAllSections();
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
		// once the window has the old one's size ({@link #settle}): loading clamps it to the hidden start-up window's 2.
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
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		SwapWindowAccess access = (SwapWindowAccess) (Object) w;
		// GLFW's framebuffer now against the one the window last took.
		int[] fw = new int[1];
		int[] fh = new int[1];
		GLFW.glfwGetFramebufferSize(w.handle(), fw, fh);
		boolean sized = fw[0] == w.getWidth() && fh[0] == w.getHeight();
		if (!sized) {
			// Minecraft hadn't heard of the new size yet (GLFW tells it by an event, later): its own resize handler, now.
			if (fw[0] > 0 && fh[0] > 0) {
				access.flint$onFramebufferResize(w.handle(), fw[0], fh[0]);
			}
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
		if (w.getGuiScale() != wanted) {
			mc.resizeGui();
			FlintClient.LOGGER.info("Flint: Instant Swap: GUI scale {} (options {})", w.getGuiScale(), mc.options.guiScale().get());
		}
	}

	@Override
	public boolean fitted() {
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		return wantedGuiScale < 0 && w.getGuiScale() == w.calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
	}

	@Override
	public String fit() {
		Minecraft mc = Minecraft.getInstance();
		Window w = mc.getWindow();
		return w.getWidth() + "x" + w.getHeight() + " GUI scale " + w.getGuiScale() + " (options " + mc.options.guiScale().get() + ")";
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
		if (mc.level != null && mc.gui.screen() != null) {
			mc.gui.setScreen(null);
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
		return data == null || data.isRealm() || mc.hasSingleplayerServer() ? null : data.ip;
	}

	@Override
	public JsonObject window() {
		Minecraft mc = Minecraft.getInstance();
		long handle = mc.getWindow().handle();
		JsonObject out = new JsonObject();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer a = stack.mallocInt(1);
			IntBuffer b = stack.mallocInt(1);
			GLFW.glfwGetWindowPos(handle, a, b);
			out.addProperty("x", a.get(0));
			out.addProperty("y", b.get(0));
			GLFW.glfwGetWindowSize(handle, a, b);
			out.addProperty("width", Math.max(320, a.get(0)));
			out.addProperty("height", Math.max(240, b.get(0)));
		}
		out.addProperty("maximized", GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE);
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
			mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
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
		long handle = w.handle();
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
					w.updateFullscreenIfChanged();
				}
			}
			settle();
		} catch (RuntimeException | LinkageError e) {
			FlintClient.LOGGER.warn("Flint: Instant Swap: couldn't place the window; it shows as it is", e);
		}
	}

	private boolean maximizeOnReveal;

	@Override
	public void reveal() {
		long handle = Minecraft.getInstance().getWindow().handle();
		// Shown CLOAKED (Windows' compositor keeps it off the screen while the game draws into it), so the first picture of
		// it anyone can ever see is a frame the game drew, never the empty (white) window. Not raised: once uncloaked it
		// sits under the launcher's still (always on top), which goes once this window has drawn on screen.
		cloak(handle, true);
		// GLFW would also take the focus on show (GLFW_FOCUS_ON_SHOW): not yet, front() does that once the still can go.
		GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
		GLFW.glfwShowWindow(handle);
		if (maximizeOnReveal) {
			GLFW.glfwMaximizeWindow(handle);
		}
		settle();
	}

	@Override
	public void preSave() {
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			long start = System.currentTimeMillis();
			// What an autosave does: players and chunks written, not waited for on disk (no flush).
			server.saveEverything(true, false, false);
			FlintClient.LOGGER.info("Flint: Instant Swap: world saved ahead in {} ms (while still playing)", System.currentTimeMillis() - start);
		});
	}

	@Override
	public boolean levelLoaded() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null;
	}

	@Override
	public void hide() {
		GLFW.glfwHideWindow(Minecraft.getInstance().getWindow().handle());
	}

	@Override
	public boolean drawsWorld() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.player != null && mc.gui.overlay() == null;
	}

	@Override
	public void keepActive() {
		// As if the player had just touched it: no AFK throttle (10 FPS) while it comes in and settles.
		Minecraft.getInstance().getFramerateLimitTracker().onInputReceived();
	}

	@Override
	public void uncloak() {
		cloak(Minecraft.getInstance().getWindow().handle(), false);
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
	public void front() {
		long handle = Minecraft.getInstance().getWindow().handle();
		GLFW.glfwFocusWindow(handle);
		settle();
	}

	@Override
	public void join(@Nullable String world, @Nullable String server) {
		Minecraft mc = Minecraft.getInstance();
		if (world != null) {
			mc.createWorldOpenFlows().openWorld(world, () -> mc.gui.setScreen(new TitleScreen()));
		} else if (server != null) {
			ServerData data = new ServerData(server, server, ServerData.Type.OTHER);
			ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server), data, false, null);
		}
	}

	@Override
	public void notice(String text) {
		Mc263NoticeToast.show(text);
	}

	@Override
	public void captureFrame(Path file, Runnable done) {
		net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(), image -> {
			try (image) {
				image.writeToFile(file);
			} catch (java.io.IOException | RuntimeException e) {
				FlintClient.LOGGER.debug("Flint: Instant Swap: couldn't write the still frame", e);
			} finally {
				done.run();
			}
		});
	}

	/** BELOW_NORMAL_PRIORITY_CLASS / NORMAL_PRIORITY_CLASS (Windows). Never PROCESS_MODE_BACKGROUND: its very low disk and
	 * memory priority starved a standby's loading for minutes. */
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
