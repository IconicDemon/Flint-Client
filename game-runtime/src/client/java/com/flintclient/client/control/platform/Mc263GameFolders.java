package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.files.PackFixer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import org.jspecify.annotations.Nullable;

/**
 * The game's resource pack, shader pack and world folders on this Minecraft (the shared {@link PackFixer} does the work, as
 * on every line): it starts looking after them, looks again every second while a screen showing one of them is open (the
 * player may be putting a file in through "Open Pack Folder"), and shows what it says: a notice, and the screen showing the
 * folder brought up to date (Minecraft's pack screen watches its own folder; the world list is opened again, Iris's shader
 * pack list refreshed).
 */
public final class Mc263GameFolders {
	private static final int LOOK_TICKS = 20;
	/** Iris's shader pack screen (an optional mod: found by name). */
	private static final String IRIS_SCREEN = "net.irisshaders.iris.gui.screen.ShaderPackScreen";
	private static @Nullable Path gameDir;
	private static boolean lookedAtStart;
	private static @Nullable Screen watched;
	private static PackFixer.@Nullable Kind watchedKind;
	private static int ticks;

	private Mc263GameFolders() {
	}

	/** At start: the game folder is looked after from now on. */
	public static void start(Path dir) {
		gameDir = dir.toAbsolutePath().normalize();
		PackFixer.start(dir, (kind, message, changed) -> {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.execute(() -> said(minecraft, kind, message, changed));
		});
	}

	/** Every client tick. */
	public static void tick() {
		if (gameDir == null) {
			return;
		}
		Screen screen = Minecraft.getInstance().gui.screen();
		if (!lookedAtStart && screen != null) {
			// Out of the way of the game's own loading: a few seconds after the title screen is up.
			lookedAtStart = true;
			PackFixer.lookAll(false, 4000);
		}
		if (screen != watched) {
			watched = screen;
			watchedKind = screen == null ? null : kindShown(screen);
			ticks = 0;
		}
		if (watchedKind != null && ticks++ % LOOK_TICKS == 0) {
			PackFixer.look(watchedKind, true);
		}
	}

	/** Which of the game's folders {@code screen} shows (Minecraft's resource pack screen, the world list, Iris's shader packs), or null. */
	static PackFixer.@Nullable Kind kindShown(Screen screen) {
		if (screen instanceof PackSelectionScreen) {
			// The resource pack screen; the data pack screens show other folders.
			Path folder = fieldOfType(screen, PackSelectionScreen.class, Path.class) instanceof Path path ? path : null;
			return folder != null && same(folder, gameDir.resolve(PackFixer.Kind.RESOURCE_PACKS.folder())) ? PackFixer.Kind.RESOURCE_PACKS : null;
		}
		if (screen instanceof SelectWorldScreen) {
			return PackFixer.Kind.WORLDS;
		}
		return screen.getClass().getName().equals(IRIS_SCREEN) ? PackFixer.Kind.SHADER_PACKS : null;
	}

	private static void said(Minecraft minecraft, PackFixer.Kind kind, String message, boolean changed) {
		Mc263NoticeToast.show(message);
		Screen screen = minecraft.gui.screen();
		if (!changed || screen == null) {
			return;
		}
		try {
			if (kind == PackFixer.Kind.WORLDS && screen instanceof SelectWorldScreen) {
				// The list reads the saves folder as it opens.
				Screen parent = fieldOfType(screen, SelectWorldScreen.class, Screen.class) instanceof Screen s ? s : null;
				minecraft.gui.setScreen(new SelectWorldScreen(parent));
			} else if (kind == PackFixer.Kind.SHADER_PACKS && screen.getClass().getName().equals(IRIS_SCREEN)) {
				refreshShaderList(screen);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			FlintClient.LOGGER.debug("Flint: couldn't bring the {} screen up to date; it is when opened again", kind.folder(), e);
		}
	}

	/** Iris's shader pack screen lists its folder again (its list's own refresh, as its Refresh button does). */
	static void refreshShaderList(Screen screen) throws ReflectiveOperationException {
		Field field = screen.getClass().getDeclaredField("shaderPackList");
		field.setAccessible(true);
		Object list = field.get(screen);
		Method refresh = list.getClass().getMethod("refresh");
		refresh.invoke(list);
	}

	/** Whether {@code screen} is Iris's shader pack screen (Iris is optional: found by name). */
	static boolean isShaderScreen(@Nullable Screen screen) {
		return screen != null && screen.getClass().getName().equals(IRIS_SCREEN);
	}

	/** The value of {@code owner}'s one instance field of {@code type} (found by type: names differ between lines). */
	private static @Nullable Object fieldOfType(Object object, Class<?> owner, Class<?> type) {
		try {
			for (Field field : owner.getDeclaredFields()) {
				if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) {
					field.setAccessible(true);
					return field.get(object);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			FlintClient.LOGGER.debug("Flint: couldn't read a {} of {}", type.getSimpleName(), owner.getSimpleName(), e);
		}
		return null;
	}

	private static boolean same(Path a, Path b) {
		try {
			return Files.isSameFile(a, b);
		} catch (java.io.IOException e) {
			return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
		}
	}
}
