package com.flintclient.legacy.swap;

import com.flintclient.client.module.social.InstantSwap;
import com.flintclient.legacy.Flint;
import com.flintclient.legacy.FriendNotices189;
import com.flintclient.legacy.mixin.swap.SwapMinecraftAccess;
import com.flintclient.legacy.mixin.swap.SwapWorldRendererAccess;
import com.flintclient.shaded.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.client.options.ServerListEntry;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.texture.TextureUtil;
import net.minecraft.client.resource.pack.ResourcePacks;
import net.minecraft.client.render.pipeline.RenderTarget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.storage.WorldSaveInfo;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * Instant Swap on Minecraft 1.8.9 ({@link InstantSwap}; 26.3's is {@code Mc263SwapEngine}): Minecraft's own Disconnect (the
 * integrated server's thread waited for, so the world is saved before the new game opens it), its LWJGL 2 window
 * ({@link SwapWindow189}: hidden in a standby game, then the old window's place, size and mode), and joining a world by its
 * folder or a server by its address as Minecraft's own screens do. Found by name from {@link InstantSwap} (the 1.8.9 build
 * points it here: src/mc189/shared-sources.rules). Render thread.
 */
public final class SwapEngine189 implements InstantSwap.Engine {
	/** How long leaving waits for the integrated server to save and stop. */
	private static final long SAVE_WAIT_MILLIS = 20_000;
	private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
	private int guiScale = -1;

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	@Override
	public boolean atMainMenu() {
		Minecraft mc = mc();
		return mc.world == null && mc.screen != null && Flint.ready();
	}

	@Override
	public boolean inWorldReady() {
		Minecraft mc = mc();
		return mc.world != null && mc.player != null && mc.screen == null && mc.worldRenderer != null
			&& ((SwapWorldRendererAccess) mc.worldRenderer).flint$dirtyChunks().isEmpty();
	}

	@Override
	public @Nullable String world() {
		IntegratedServer server = mc().getServer();
		return server == null ? null : server.getWorldSaveName();
	}

	@Override
	public @Nullable String server() {
		Minecraft mc = mc();
		ServerListEntry entry = mc.getCurrentServerEntry();
		return entry == null || mc.isIntegratedServerRunning() || mc.isConnectedToRealms() ? null : entry.ip;
	}

	@Override
	public JsonObject window() {
		Minecraft mc = mc();
		JsonObject out = new JsonObject();
		out.addProperty("x", Display.getX());
		out.addProperty("y", Display.getY());
		out.addProperty("width", Math.max(320, Display.getWidth()));
		out.addProperty("height", Math.max(240, Display.getHeight()));
		out.addProperty("maximized", SwapWindow189.maximized());
		out.addProperty("fullscreen", mc.options.fullscreen);
		out.addProperty("guiScale", mc.options.guiScale);
		out.addProperty("pid", ProcessHandle.current().pid());
		return out;
	}

	@Override
	public Path gameDir() {
		return mc().gameDir.toPath();
	}

	@Override
	public void saveOptions() {
		mc().options.save();
	}

	@Override
	public boolean reloadOptions(Runnable then) {
		Minecraft mc = mc();
		List<String> before = new ArrayList<>(mc.options.resourcePacks);
		mc.options.load();
		if (!before.equals(mc.options.resourcePacks)) {
			// The player turned resource packs on or off after this game started: as Minecraft's own Done on the Resource Packs
			// screen (1.8.9 reloads on this thread, still hidden).
			ResourcePacks packs = mc.getResourcePacks();
			packs.reload();
			List<ResourcePacks.UnopenedPack> selected = new ArrayList<>();
			for (String name : mc.options.resourcePacks) {
				for (Object o : packs.getAvailablePacks()) {
					ResourcePacks.UnopenedPack p = (ResourcePacks.UnopenedPack) o;
					if (p.getName().equals(name)) {
						selected.add(p);
					}
				}
			}
			packs.setSelectedPacks(selected);
			mc.reloadResources();
			Flint.LOG.info("Flint: Instant Swap: the player's resource packs changed; loaded them first");
		}
		return false;
	}

	@Override
	public void settle() {
		Minecraft mc = mc();
		if (!Display.isCreated()) {
			return;
		}
		if (!mc.options.fullscreen && (Display.getWidth() != mc.width || Display.getHeight() != mc.height)) {
			((SwapMinecraftAccess) mc).flint$resize(Display.getWidth(), Display.getHeight());
		}
		if (guiScale >= 0 && mc.options.guiScale != guiScale) {
			mc.options.guiScale = guiScale;
			((SwapMinecraftAccess) mc).flint$resize(mc.width, mc.height);
		}
		guiScale = -1;
		// In the world with the window in front: the mouse is the game's again (it couldn't be while hidden).
		if (mc.world != null && mc.screen == null && !mc.focused && Display.isActive()) {
			mc.lockMouse();
		}
	}

	@Override
	public String fit() {
		Minecraft mc = mc();
		return mc.width + "x" + mc.height + " GUI scale " + new net.minecraft.client.render.Window(mc).getScale() + " (options " + mc.options.guiScale + ")";
	}

	@Override
	public CompletableFuture<Boolean> loadLive(Path jar) {
		return HotLoader189.load(jar).thenApply(r -> {
			Flint.LOG.info("Flint: {} {}", jar.getFileName(), r.loaded() ? "loaded live (" + r.renderThreadMs() + " ms on the render thread)"
				: "can't load live (" + r.reason() + "): Instant Swap brings it in");
			return r.loaded();
		});
	}

	@Override
	public void closeScreens() {
		Minecraft mc = mc();
		if (mc.world != null && mc.screen != null) {
			mc.openScreen(null);
		}
	}

	@Override
	public void allowForeground() {
		if (!WINDOWS) {
			return;
		}
		try {
			com.sun.jna.Function.getFunction("user32", "AllowSetForegroundWindow").invokeInt(new Object[] {-1});
		} catch (RuntimeException | LinkageError e) {
			Flint.LOG.debug("Flint: Instant Swap: couldn't hand the front on", e);
		}
	}

	@Override
	public void leave() {
		Minecraft mc = mc();
		if (mc.world == null) {
			return;
		}
		boolean singleplayer = mc.isIntegratedServerRunning();
		// What the pause menu's Save and Quit to Title (or Disconnect) runs.
		mc.world.disconnect();
		mc.setWorld(null);
		if (singleplayer) {
			// 1.8.9 saves the world on the server's own thread after this returns: the new game opens it only once that is done.
			waitForServerThread();
		}
		mc.openScreen(new TitleScreen());
	}

	private static void waitForServerThread() {
		long until = System.currentTimeMillis() + SAVE_WAIT_MILLIS;
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (!"Server thread".equals(t.getName())) {
				continue;
			}
			try {
				while (t.isAlive() && System.currentTimeMillis() < until) {
					t.join(50);
					// The window keeps answering the system (no "Not responding") while the frame is held.
					Display.processMessages();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			Flint.LOG.info("Flint: Instant Swap: the world is saved{}", t.isAlive() ? " (still saving after 20 s)" : "");
		}
	}

	@Override
	public void quit() {
		mc().stop();
	}

	/** Where the window goes on the reveal (the old one's place, maximized, fullscreen). */
	private int revealX;
	private int revealY;
	private boolean maximizeOnReveal;
	private boolean fullscreenOnReveal;

	@Override
	public void show(@Nullable JsonObject window) {
		// Still hidden: the old window's size and the player's GUI scale, so the first frame anyone can see is at its final
		// size and scale (reveal() shows it, under the launcher's still, a few frames later).
		Minecraft mc = mc();
		revealX = Display.getX();
		revealY = Display.getY();
		int width = mc.width;
		int height = mc.height;
		maximizeOnReveal = false;
		fullscreenOnReveal = false;
		if (window != null) {
			revealX = window.has("x") ? window.get("x").getAsInt() : revealX;
			revealY = window.has("y") ? window.get("y").getAsInt() : revealY;
			width = window.has("width") ? window.get("width").getAsInt() : width;
			height = window.has("height") ? window.get("height").getAsInt() : height;
			maximizeOnReveal = window.has("maximized") && window.get("maximized").getAsBoolean();
			fullscreenOnReveal = window.has("fullscreen") && window.get("fullscreen").getAsBoolean();
			guiScale = window.has("guiScale") ? window.get("guiScale").getAsInt() : -1;
		}
		SwapWindow189.resizeHidden(width, height);
		mc.width = Math.max(1, width);
		mc.height = Math.max(1, height);
		((SwapMinecraftAccess) mc).flint$resize(mc.width, mc.height);
		settle();
	}

	@Override
	public boolean fitted() {
		Minecraft mc = mc();
		return guiScale < 0 && mc.width == Display.getWidth() && mc.height == Display.getHeight();
	}

	@Override
	public void reveal() {
		// Out of the hidden canvas into its own window in the old one's place, cloaked at once (Windows' compositor keeps it off
		// the screen while the game draws into it), so the first picture of it anyone sees is a frame the game drew. Not raised:
		// uncloaked it sits under the launcher's still (always on top), which goes once this window has drawn on screen.
		Minecraft mc = mc();
		int width = mc.width;
		int height = mc.height;
		SwapWindow189.show(revealX, revealY, width, height);
		SwapWindow189.cloak(true);
		SwapWindow189.fitClient(width, height);
		org.lwjgl.opengl.Display.processMessages();
		if (maximizeOnReveal) {
			SwapWindow189.maximize();
		}
		if (fullscreenOnReveal && !mc.options.fullscreen) {
			// Started windowed while hidden; the player's fullscreen (Flint's borderless one, or Minecraft's) comes back now.
			mc.toggleFullscreen();
		}
		mc.width = Math.max(1, Display.getWidth());
		mc.height = Math.max(1, Display.getHeight());
		((SwapMinecraftAccess) mc).flint$resize(mc.width, mc.height);
		settle();
	}

	@Override
	public void hide() {
		// The old game, once the new one is drawn under the still: its frozen window goes at once.
		SwapWindow189.hideWindow();
	}

	@Override
	public boolean drawsWorld() {
		Minecraft mc = mc();
		return mc.world != null && mc.player != null;
	}

	// keepActive: 1.8.9 has no long-AFK frame limit (its 30 FPS cap is for menus without a world), nothing to keep awake.

	@Override
	public void uncloak() {
		SwapWindow189.cloak(false);
	}

	@Override
	public void front() {
		SwapWindow189.front();
		settle();
	}

	@Override
	public boolean promoted() {
		if (!WINDOWS) {
			return true;
		}
		try {
			com.sun.jna.Pointer self = com.sun.jna.Function.getFunction("kernel32", "GetCurrentProcess").invokePointer(new Object[0]);
			int priority = com.sun.jna.Function.getFunction("kernel32", "GetPriorityClass").invokeInt(new Object[] {self});
			// NORMAL, ABOVE_NORMAL or HIGH: not the standby's below normal or idle.
			return priority == 0x20 || priority == 0x8000 || priority == 0x80;
		} catch (RuntimeException | LinkageError e) {
			return true;
		}
	}

	@Override
	public void join(@Nullable String world, @Nullable String server) {
		Minecraft mc = mc();
		if (world != null) {
			String name = world;
			try {
				for (Object o : mc.getWorldStorageSource().getAll()) {
					WorldSaveInfo info = (WorldSaveInfo) o;
					if (world.equals(info.getSaveName())) {
						name = info.getName();
					}
				}
			} catch (Exception e) {
				// Its folder name, then.
			}
			mc.startGame(world, name, null);
		} else if (server != null) {
			mc.openScreen(new ConnectScreen(new TitleScreen(), mc, new ServerListEntry(server, server, false)));
		}
	}

	@Override
	public void notice(String text) {
		FriendNotices189.show(text);
	}

	@Override
	public void background(boolean on) {
		if (!WINDOWS) {
			return;
		}
		try {
			com.sun.jna.Pointer self = com.sun.jna.Function.getFunction("kernel32", "GetCurrentProcess").invokePointer(new Object[0]);
			int done = com.sun.jna.Function.getFunction("kernel32", "SetPriorityClass").invokeInt(new Object[] {self, on ? 0x00004000 : 0x00000020});
			Flint.LOG.info("Flint: Instant Swap: priority {} ({})", on ? "below normal" : "normal", done != 0 ? "ok" : "refused");
		} catch (RuntimeException | LinkageError e) {
			Flint.LOG.debug("Flint: Instant Swap: no background mode", e);
		}
	}

	@Override
	public void captureFrame(Path file, Runnable done) {
		// The world as drawn last (Minecraft's own framebuffer, as F2 reads it), read here and written to disk off this thread.
		Minecraft mc = mc();
		RenderTarget target = ((SwapMinecraftAccess) mc).flint$renderTarget();
		int width;
		int height;
		IntBuffer pixels;
		int[] argb;
		if (GLX.useFbo() && target != null) {
			width = target.width;
			height = target.height;
			pixels = BufferUtils.createIntBuffer(width * height);
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
			GlStateManager.bindTexture(target.colorTextureId);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, 0x80E1, 0x8367, pixels);
		} else {
			width = mc.width;
			height = mc.height;
			pixels = BufferUtils.createIntBuffer(width * height);
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
			GL11.glReadPixels(0, 0, width, height, 0x80E1, 0x8367, pixels);
		}
		argb = new int[width * height];
		pixels.get(argb);
		int viewWidth = target != null && GLX.useFbo() ? target.viewWidth : width;
		int viewHeight = target != null && GLX.useFbo() ? target.viewHeight : height;
		int w = width;
		int h = height;
		Thread writer = new Thread(() -> {
			try {
				TextureUtil.flipImageVertically(argb, w, h);
				BufferedImage image = new BufferedImage(viewWidth, viewHeight, BufferedImage.TYPE_INT_RGB);
				int skip = h - viewHeight;
				for (int row = 0; row < viewHeight; row++) {
					image.setRGB(0, row, viewWidth, 1, argb, (row + skip) * w, w);
				}
				javax.imageio.ImageIO.write(image, "png", new File(file.toString()));
			} catch (Exception | LinkageError e) {
				Flint.LOG.debug("Flint: Instant Swap: couldn't write the still frame", e);
			} finally {
				done.run();
			}
		}, "Flint swap frame");
		writer.setDaemon(true);
		writer.start();
	}
}
