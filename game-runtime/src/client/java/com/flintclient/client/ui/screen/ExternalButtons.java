package com.flintclient.client.ui.screen;

import com.flintclient.client.FlintClient;
import com.flintclient.client.compat.McCompat;
import com.flintclient.client.platform.Images;
import com.flintclient.client.platform.Texture;
import com.flintclient.client.render.Pixels;
import com.flintclient.client.render.WhiteMark;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

/**
 * The buttons other mods put on Minecraft's title screen (Flashback's replays, Replay Mod's viewer, Mod Menu's Mods…), for Flint's
 * main menu, which replaces that screen and so never gets them. They are found the way they are made: a Minecraft title screen is
 * laid out out of sight ({@link McCompat#initScreen}: its own init, every mod's hooks on it, Fabric API's screen events), and
 * whatever is on it that Minecraft's own title screen doesn't have is another mod's. Each becomes a small icon on Flint's menu
 * (the mod's own icon, "Flashback · External"), and pressing it presses that mod's own button ({@link McCompat#clickWidget}),
 * so it opens exactly what the mod opens; coming back lands on Flint's menu (the screen that button returns to is a Minecraft
 * title screen, which Flint's menu takes the place of).
 *
 * <p>Nothing here depends on a particular mod: a mod that changes or drops its button changes or drops its icon, and anything
 * that fails (a mod's hook throwing on the hidden screen, an unreadable icon) leaves that button out and is logged once.
 */
public final class ExternalButtons {
	/** The most icons the menu shows. */
	static final int MAX = 6;
	/** Minecraft's own title screen controls, by the key of their text (any other control is another mod's). */
	private static final Set<String> VANILLA = Set.of("menu.singleplayer", "menu.multiplayer", "menu.online", "menu.options", "menu.quit",
		"menu.playdemo", "menu.resetdemo", "title.credits", "options.language", "options.accessibility",
		"accessibility.onboarding.accessibility.button", "narrator.button.language", "narrator.button.accessibility", "gui.friends.open");
	/** Mods whose buttons are Flint's own business or never another mod's. */
	private static final Set<String> SKIPPED = Set.of("minecraft", "java", "fabricloader", FlintClient.MOD_ID);

	/** True while the hidden title screen is laid out (Flint's own additions to Minecraft's title screen stay off it). */
	private static boolean probing;
	private static boolean warned;
	/** Each mod's icon, read once per session (empty: it has none). */
	private static final Map<String, Optional<Icon>> ICONS = new HashMap<>();
	private static @Nullable Map<Path, ModContainer> origins;

	private ExternalButtons() {
	}

	/** A mod's icon as a texture, and its size. */
	record Icon(Texture texture, int width, int height) {
	}

	/** One button: the mod's name ("Flashback"), its icon (or none: its initial), and the mod's own control it presses. */
	record Entry(String name, @Nullable Icon icon, AbstractWidget widget) {
		/** Presses the mod's own button. False when it didn't take the press. */
		boolean press() {
			try {
				return widget.active && widget.visible && McCompat.clickWidget(widget);
			} catch (RuntimeException | LinkageError e) {
				FlintClient.LOGGER.warn("Flint: {}'s title screen button failed", name, e);
				return false;
			}
		}
	}

	/** Whether the hidden title screen is being laid out right now. */
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
			McCompat.initScreen(probe, Math.max(320, guiWidth), Math.max(240, guiHeight));
		} catch (RuntimeException | LinkageError e) {
			warn("laying out Minecraft's title screen out of sight", e);
			return out;
		} finally {
			probing = false;
		}
		try {
			Map<String, Integer> seen = new HashMap<>();
			for (GuiEventListener child : probe.children()) {
				if (!(child instanceof AbstractWidget widget) || !widget.visible || vanilla(widget)) {
					continue;
				}
				ModContainer mod = owner(widget);
				if (mod != null && SKIPPED.contains(mod.getMetadata().getId())) {
					continue;
				}
				String name = mod != null ? clean(mod.getMetadata().getName()) : clean(widget.getMessage().getString());
				if (name.isEmpty()) {
					continue;
				}
				// Two buttons of one mod keep apart by their own words.
				int n = seen.merge(name, 1, Integer::sum);
				String shown = n == 1 ? name : name + " (" + clean(widget.getMessage().getString()) + ")";
				out.add(new Entry(shown, mod == null ? null : icon(mod), widget));
				if (out.size() >= MAX) {
					break;
				}
			}
		} catch (RuntimeException | LinkageError e) {
			warn("reading the buttons other mods put on Minecraft's title screen", e);
		} finally {
			try {
				// Minecraft's title screen lets go of what it started (Realms' notifications) when it goes.
				probe.removed();
			} catch (RuntimeException | LinkageError e) {
				// Nothing more to undo.
			}
		}
		return out;
	}

	/** Minecraft's own title screen control (one of its texts), or Flint's own. */
	private static boolean vanilla(AbstractWidget widget) {
		if (widget instanceof com.flintclient.client.ui.widget.FlintButton) {
			return true;
		}
		String type = widget.getClass().getName();
		boolean minecraftClass = type.startsWith("net.minecraft.") || type.startsWith("com.mojang.");
		Component message = widget.getMessage();
		if (message.getContents() instanceof TranslatableContents translated) {
			return minecraftClass && VANILLA.contains(translated.getKey());
		}
		// The IDE-only test world button ("TW"), and Minecraft's own unlabelled controls (the copyright line).
		return minecraftClass && (message.getString().equals("TW") || message.getString().isEmpty());
	}

	/**
	 * The mod a control belongs to: the mod whose jar its class came from, else the mod named by the first part of its text's
	 * key ({@code flashback.open_replays}, {@code modmenu.title}, {@code replaymod.gui.replayviewer}); null when neither says.
	 */
	private static @Nullable ModContainer owner(AbstractWidget widget) {
		ModContainer byClass = byClass(widget.getClass());
		if (byClass != null && !SKIPPED.contains(byClass.getMetadata().getId())) {
			return byClass;
		}
		if (widget.getMessage().getContents() instanceof TranslatableContents translated) {
			String key = translated.getKey();
			int dot = key.indexOf('.');
			String prefix = (dot > 0 ? key.substring(0, dot) : key).toLowerCase(Locale.ROOT);
			Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(prefix);
			if (mod.isPresent()) {
				return mod.get();
			}
		}
		return byClass;
	}

	private static @Nullable ModContainer byClass(Class<?> type) {
		try {
			CodeSource source = type.getProtectionDomain().getCodeSource();
			URL location = source == null ? null : source.getLocation();
			if (location == null || !"file".equals(location.getProtocol())) {
				return null;
			}
			Path path = Path.of(location.toURI()).toAbsolutePath().normalize();
			return origins().get(path);
		} catch (Exception | LinkageError e) {
			return null;
		}
	}

	/** Every mod's jar (or folder), by its path. */
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
					// A nested jar without a path of its own: found by its text's key instead.
				}
			}
			origins = map;
		}
		return map;
	}

	/**
	 * The mod's own icon (fabric.mod.json's) as a white mark ({@link WhiteMark}: it sits with Flint's white icons, not in the
	 * mod's colours), uploaded once per session; null when it has none, it can't be read or no mark is left of it.
	 */
	private static @Nullable Icon icon(ModContainer mod) {
		String id = mod.getMetadata().getId();
		return ICONS.computeIfAbsent(id, key -> {
			try {
				Optional<String> file = mod.getMetadata().getIconPath(64);
				if (file.isEmpty()) {
					return Optional.empty();
				}
				Optional<Path> path = mod.findPath(file.get());
				if (path.isEmpty() || Files.size(path.get()) > 512 * 1024) {
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

	/** A mod's name as a tooltip shows it: one line, no formatting codes, at most 40 characters. */
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
		return out.toString().strip();
	}

	private static void warn(String what, Throwable error) {
		if (!warned) {
			warned = true;
			FlintClient.LOGGER.warn("Flint: other mods' title screen buttons are left off Flint's menu ({})", what, error);
		}
	}

}
