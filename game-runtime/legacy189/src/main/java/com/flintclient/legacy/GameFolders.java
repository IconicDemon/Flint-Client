package com.flintclient.legacy;

import com.flintclient.client.files.PackFixer;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.resourcepacks.ResourcePacksScreen;
import net.minecraft.client.gui.screen.menu.world.SelectWorldScreen;
import net.minecraft.client.render.TextRenderer;
import org.jspecify.annotations.Nullable;

/**
 * 1.8.9's resource pack and world folders (the shared PackFixer does the work, as on every line): it starts looking after
 * them, looks again every second while the Resource Packs or Select World screen is open (the player may be putting a file
 * in through "Open resource pack folder"), and shows what it says: a notice over the screen (1.8.9 has no toasts), and the
 * screen brought up to date. 1.8.9 has no shader packs.
 */
public final class GameFolders {
	private static final int LOOK_TICKS = 20;
	private static final long NOTICE_MILLIS = 4500;
	/** What the worker said, for the game's thread. */
	private static final ConcurrentLinkedQueue<Said> SAID = new ConcurrentLinkedQueue<>();
	private static boolean lookedAtStart;
	private static @Nullable Screen watched;
	private static int ticks;
	private static @Nullable String notice;
	private static long noticeAt;

	private record Said(PackFixer.Kind kind, String message, boolean changed) {
	}

	private GameFolders() {
	}

	static void start(java.nio.file.Path gameDir) {
		PackFixer.start(gameDir, (kind, message, changed) -> SAID.add(new Said(kind, message, changed)));
	}

	/** Every client tick. */
	static void tick(Minecraft mc) {
		Screen screen = mc.screen;
		if (!lookedAtStart && screen != null) {
			// The title screen is up: the log's start-up line (as on every line), then, out of the way of the game's own
			// loading, a look at the folders a few seconds later.
			lookedAtStart = true;
			com.flintclient.client.StartupTrace.titleShown();
			PackFixer.lookAll(false, 4000);
		}
		if (screen != watched) {
			watched = screen;
			ticks = 0;
		}
		PackFixer.Kind kind = screen instanceof ResourcePacksScreen ? PackFixer.Kind.RESOURCE_PACKS
			: screen instanceof SelectWorldScreen ? PackFixer.Kind.WORLDS : null;
		if (kind != null && ticks++ % LOOK_TICKS == 0) {
			PackFixer.look(kind, true);
		}
		for (Said said; (said = SAID.poll()) != null; ) {
			show(mc, said);
		}
	}

	private static void show(Minecraft mc, Said said) {
		String message = said.message;
		Screen screen = mc.screen;
		if (said.changed && screen != null) {
			if (said.kind == PackFixer.Kind.WORLDS && screen instanceof SelectWorldScreen) {
				// Its init reads the saves folder again.
				screen.init(mc, screen.width, screen.height);
			} else if (said.kind == PackFixer.Kind.RESOURCE_PACKS && screen instanceof ResourcePacksScreen) {
				// Its init reads the folder again, unless the player already moved packs around (that would be lost).
				if (changedByPlayer(screen)) {
					message += " (reopen this screen to see it)";
				} else {
					screen.init(mc, screen.width, screen.height);
				}
			}
		}
		if (screen == null && mc.world != null) {
			mc.gui.setOverlayMessage(message, false);
		} else {
			notice = message;
			noticeAt = System.currentTimeMillis();
		}
	}

	/** ResourcePacksScreen's own "changed" flag (its only boolean field). */
	static boolean changedByPlayer(Screen screen) {
		try {
			for (Field field : ResourcePacksScreen.class.getDeclaredFields()) {
				if (field.getType() == boolean.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					field.setAccessible(true);
					return field.getBoolean(screen);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			Flint.LOG.debug("Flint: couldn't read the resource pack screen's state", e);
		}
		return true;
	}

	/** After the screen is drawn: the notice, at the top, fading out at its end. */
	public static void drawNotice(Minecraft mc, Screen screen) {
		String text = notice;
		if (text == null) {
			return;
		}
		long age = System.currentTimeMillis() - noticeAt;
		if (age > NOTICE_MILLIS) {
			notice = null;
			return;
		}
		float fade = Math.min(1.0F, (NOTICE_MILLIS - age) / 400.0F);
		int alpha = Math.max(4, Math.round(fade * 255));
		TextRenderer font = mc.textRenderer;
		int width = font.getWidth(text);
		int x = (screen.width - width) / 2;
		// Above the screen's own title (drawn at y 15 or lower).
		int y = 3;
		GuiElement.fill(x - 6, y - 2, x + width + 6, y + font.fontHeight + 1, (Math.round(fade * 0xC0) << 24) | 0x0A0908);
		GuiElement.fill(x - 6, y + font.fontHeight + 1, x + width + 6, y + font.fontHeight + 2, (alpha << 24) | 0xE07A1F);
		font.drawWithShadow(text, x, y, (alpha << 24) | 0xFFFFFF);
	}
}
