package com.flintclient.client.ui.screen;

import com.flintclient.client.platform.Images;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Pixels;
import com.flintclient.client.render.WhiteMark;
import com.flintclient.legacy.mixin.ScreenButtonsAccessor;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.apache.logging.log4j.LogManager;
import org.jspecify.annotations.Nullable;

/**
 * The buttons other mods put on Minecraft 1.8.9's title screen, for Flint's main menu, which replaces that screen and so never gets
 * them: the same idea as on every line (src/client's ExternalButtons), in 1.8.9's terms. A Minecraft title screen is laid out
 * out of sight (its own {@code init} and every mod's hooks on it), and each button on it that isn't one of Minecraft's own (by
 * its id: Singleplayer 1, Multiplayer 2, Realms 14, Options 0, Quit 4, Language 5, the demo's 11 and 12) becomes an icon on
 * Flint's menu: its mod's own icon and name ("Replay Mod · External"). Pressing it hands that button to the hidden screen's own
 * {@code buttonClicked}, where the mod answers it, so it opens exactly what the mod opens; coming back lands on Flint's menu
 * (that screen is a Minecraft title screen, which Flint's menu takes the place of).
 */
public final class ExternalButtons {
	static final int MAX = 6;
	/** Minecraft 1.8.9's own title screen buttons, by id. */
	private static final Set<Integer> VANILLA_IDS = Set.of(0, 1, 2, 4, 5, 11, 12, 14);
	private static final Set<String> SKIPPED = Set.of("minecraft", "java", "fabricloader", "flintclient");

	private static boolean probing;
	private static boolean warned;
	private static final Map<String, Optional<Icon>> ICONS = new HashMap<>();
	private static @Nullable Map<Path, ModContainer> origins;

	private ExternalButtons() {
	}

	/** A mod's icon as a texture, and its size. */
	record Icon(Texture texture, int width, int height) {
	}

	/** One button: the mod's name, its icon (or none: its initial), and what pressing it does. */
	record Entry(String name, @Nullable Icon icon, Runnable action) {
		boolean press() {
			try {
				action.run();
				return true;
			} catch (RuntimeException | LinkageError e) {
				LogManager.getLogger("Flint").warn("Flint: {}'s title screen button failed", name, e);
				return false;
			}
		}
	}

	/** Whether the hidden title screen is being laid out right now (Flint's own additions to it stay off). */
	public static boolean probing() {
		return probing;
	}

	/** The other mods' title-screen buttons, for a screen {@code guiWidth} × {@code guiHeight} GUI units; empty when there are none. */
	static List<Entry> find(int guiWidth, int guiHeight) {
		List<Entry> out = new ArrayList<>();
		TitleScreen probe;
		probing = true;
		try {
			probe = new TitleScreen();
			probe.init(Minecraft.getInstance(), Math.max(320, guiWidth), Math.max(240, guiHeight));
		} catch (RuntimeException | LinkageError e) {
			warn("laying out Minecraft's title screen out of sight", e);
			return out;
		} finally {
			probing = false;
		}
		try {
			ScreenButtonsAccessor access = (ScreenButtonsAccessor) probe;
			Map<String, Integer> seen = new HashMap<>();
			for (ButtonWidget button : new ArrayList<>(access.flint$buttons())) {
				if (!button.visible || vanilla(button)) {
					continue;
				}
				ModContainer mod = byClass(button.getClass());
				if (mod != null && SKIPPED.contains(mod.getMetadata().getId())) {
					continue;
				}
				String words = clean(button.message);
				String name = mod != null ? clean(mod.getMetadata().getName()) : words;
				if (name.isEmpty()) {
					continue;
				}
				int n = seen.merge(name, 1, Integer::sum);
				String shown = n == 1 || words.isEmpty() ? name : name + " (" + words + ")";
				out.add(new Entry(shown, mod == null ? null : icon(mod), () -> {
					if (button.active) {
						access.flint$buttonClicked(button);
					}
				}));
				if (out.size() >= MAX) {
					break;
				}
			}
		} catch (RuntimeException | LinkageError e) {
			warn("reading the buttons other mods put on Minecraft's title screen", e);
		}
		return out;
	}

	/** Minecraft's own title screen button: one of its ids, on one of Minecraft's own button classes. */
	private static boolean vanilla(ButtonWidget button) {
		String type = button.getClass().getName();
		return VANILLA_IDS.contains(button.id) && type.startsWith("net.minecraft.");
	}

	/** The mod whose jar a button's class came from (a mod's own button class), or null. */
	private static @Nullable ModContainer byClass(Class<?> type) {
		if (type.getName().startsWith("net.minecraft.")) {
			return null;
		}
		try {
			CodeSource source = type.getProtectionDomain().getCodeSource();
			URL location = source == null ? null : source.getLocation();
			if (location == null || !"file".equals(location.getProtocol())) {
				return null;
			}
			return origins().get(Path.of(location.toURI()).toAbsolutePath().normalize());
		} catch (Exception | LinkageError e) {
			return null;
		}
	}

	private static Map<Path, ModContainer> origins() {
		Map<Path, ModContainer> map = origins;
		if (map == null) {
			map = new HashMap<>();
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				try {
					for (Path path : mod.getOrigin().getPaths()) {
						map.putIfAbsent(path.toAbsolutePath().normalize(), mod);
					}
				} catch (RuntimeException | LinkageError e) {
					// A nested jar without a path of its own: its button shows by its own words.
				}
			}
			origins = map;
		}
		return map;
	}

	/**
	 * The mod's own icon (fabric.mod.json's) as a white mark ({@link WhiteMark}, as on every line), uploaded once per session;
	 * null when it has none, it can't be read or no mark is left of it.
	 */
	private static @Nullable Icon icon(ModContainer mod) {
		String id = mod.getMetadata().getId();
		return ICONS.computeIfAbsent(id, key -> {
			try {
				Optional<String> file = mod.getMetadata().getIconPath(64);
				if (!file.isPresent()) {
					return Optional.empty();
				}
				Optional<Path> path = mod.findPath(file.get());
				if (!path.isPresent() || Files.size(path.get()) > 512 * 1024) {
					return Optional.empty();
				}
				Pixels pixels = Images.decode(Files.readAllBytes(path.get()));
				if (pixels == null) {
					return Optional.empty();
				}
				Pixels mark = WhiteMark.of(com.flintclient.client.module.social.MenuArt.fitPixels(pixels, 128, 128));
				if (mark == null) {
					return Optional.empty();
				}
				Texture texture = Texture.upload("external/" + key.replaceAll("[^a-z0-9_.-]", "_"), mark.width(), mark.height(), mark.argb(), true);
				return Optional.of(new Icon(texture, mark.width(), mark.height()));
			} catch (Exception | LinkageError e) {
				return Optional.empty();
			}
		}).orElse(null);
	}

	/** A name as a tooltip shows it: one line, no formatting codes, at most 40 characters. */
	private static String clean(@Nullable String text) {
		if (text == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length() && out.length() < 40; i++) {
			char c = text.charAt(i);
			if (c == '§') {
				i++;
			} else if (c >= ' ' && c != 0x7F) {
				out.append(c);
			}
		}
		return out.toString().trim();
	}

	private static void warn(String what, Throwable error) {
		if (!warned) {
			warned = true;
			LogManager.getLogger("Flint").warn("Flint: other mods' title screen buttons are left off Flint's menu ({})", what, error);
		}
	}
}
