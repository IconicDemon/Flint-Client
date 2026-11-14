package com.flintclient.client.mc;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;

/**
 * Newer Minecraft's game object as Flint's main menu uses it, over Minecraft 1.8.9's (see {@link Component}): its screens
 * ({@link Gui}), options, folders, account, window, clipboard and render thread. One object; render thread unless noted.
 */
public final class Minecraft implements Executor {
	private static final Minecraft INSTANCE = new Minecraft();

	public final Gui gui = new Gui();
	public final Options options = new Options();
	public final File gameDirectory;
	public final KeyboardHandler keyboardHandler = new KeyboardHandler();
	/** The world being played, or null (as newer Minecraft's {@code level}): brought up to date by {@link #getInstance}. */
	public @Nullable Object level;
	private final Window window = new Window();
	private final TextureManager textures = new TextureManager();

	private Minecraft() {
		gameDirectory = game().gameDir;
	}

	public static Minecraft getInstance() {
		INSTANCE.level = game().world;
		return INSTANCE;
	}

	/** Minecraft 1.8.9's own game object. */
	static net.minecraft.client.Minecraft game() {
		return net.minecraft.client.Minecraft.getInstance();
	}

	public User getUser() {
		return new User(game().getSession());
	}

	public boolean isDemo() {
		return game().isDemo();
	}

	/** Quit Game. */
	public void stop() {
		game().stop();
	}

	public Window getWindow() {
		return window;
	}

	public TextureManager getTextureManager() {
		return textures;
	}

	public InputType getLastInputType() {
		return Screen.keyboardInput ? InputType.KEYBOARD : InputType.MOUSE;
	}

	public Path getResourcePackDirectory() {
		return gameDirectory.toPath().resolve("resourcepacks");
	}

	public Object getLanguageManager() {
		return game().getLanguageManager();
	}

	public boolean isSameThread() {
		return game().isOnSameThread();
	}

	/** Runs {@code task} on the render thread (at once when already on it). Any thread. */
	@Override
	public void execute(Runnable task) {
		if (game().isOnSameThread()) {
			task.run();
		} else {
			game().executeTask(task);
		}
	}

	/** The screens: newer Minecraft's {@code Minecraft.gui}. */
	public static final class Gui {
		private Gui() {
		}

		/**
		 * Shows {@code screen} (a 1.8.9 screen, Flint's included; null: the game, or the title screen without a world). Newer
		 * Minecraft's title screen ({@link TitleScreen} itself, as {@code new TitleScreen()} asks for it) is Minecraft 1.8.9's;
		 * Flint's main menu takes its place when the player chose it (the 1.8.9 {@code MinecraftMixin}, as on every line).
		 */
		public void setScreen(net.minecraft.client.gui.screen.@Nullable Screen screen) {
			net.minecraft.client.gui.screen.Screen shown = screen != null && screen.getClass() == TitleScreen.class
				? new net.minecraft.client.gui.screen.menu.TitleScreen() : screen;
			net.minecraft.client.Minecraft mc = game();
			mc.openScreen(shown);
			if (mc.screen == null && mc.world != null) {
				mc.lockMouse();
			}
		}

		/** The screen shown now when it is one of newer Minecraft's kind (Flint's main menu or a page of it), else null. */
		public @Nullable Screen screen() {
			return game().screen instanceof Screen screen ? screen : null;
		}
	}

	/** The options Flint's menu reads; 1.8.9's own are {@link #game()}. */
	public static final class Options {
		/** 1.8.9 shows no multiplayer safety notice. */
		public boolean skipMultiplayerWarning = true;

		private Options() {
		}

		public net.minecraft.client.options.GameOptions game() {
			return Minecraft.game().options;
		}
	}

	/** The account the game plays as. */
	public static final class User {
		private final net.minecraft.client.Session session;

		User(net.minecraft.client.Session session) {
			this.session = session;
		}

		public String getName() {
			return session.getUsername();
		}

		/** Its UUID (1.8.9 keeps it as text, with or without dashes). */
		public UUID getProfileId() {
			String hex = session.getUuid() == null ? "" : session.getUuid().replace("-", "");
			if (hex.length() != 32) {
				return new UUID(0, 0);
			}
			return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
		}
	}

	/** How the player last used the menu: newer Minecraft shows focus rings only after keyboard use. */
	public enum InputType {
		MOUSE,
		KEYBOARD;

		public boolean isKeyboard() {
			return this == KEYBOARD;
		}
	}

	/** The clipboard. */
	public static final class KeyboardHandler {
		private KeyboardHandler() {
		}

		public void setClipboard(String text) {
			net.minecraft.client.gui.screen.Screen.setClipboard(text);
		}

		public String getClipboard() {
			return net.minecraft.client.gui.screen.Screen.getClipboard();
		}
	}
}
