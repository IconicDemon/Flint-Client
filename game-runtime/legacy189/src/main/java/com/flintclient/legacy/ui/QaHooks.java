package com.flintclient.legacy.ui;

import com.flintclient.client.platform.HostScreen;
import com.flintclient.client.ui.kit.KitScreen;
import com.flintclient.client.ui.kit.Node;
import com.flintclient.legacy.Flint;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * What the dev-only QA tour (qa/legacy189) reaches by reflection: the pointer it moves, a named element's bounds on Flint's
 * screens (the shared kit's on the Right Shift menu, the HUD page's own otherwise), and mods and settings it sets directly.
 * Inert in play: nothing calls it but the QA mod.
 */
public final class QaHooks {
	private QaHooks() {
	}

	/** The pointer at window pixel ({@code x}, {@code y}), as the tour moves it (the real mouse stays where it is). */
	public static void move(int x, int y) {
		Ui.qaMove(x, y);
		HostScreen.qaPointer(x, y);
	}

	/** The window-pixel bounds {x, y, w, h} of the shown element named {@code name} (equal, else starting with it), or null. */
	public static int[] bounds(String name) {
		Object screen = Minecraft.getInstance().screen;
		if (screen instanceof KitScreen kit) {
			Node node = node(kit, name);
			if (node == null) {
				return null;
			}
			int s = kit.uiScale();
			return new int[] {Math.round(node.x() * s), Math.round(node.y() * s), Math.round(node.width() * s), Math.round(node.height() * s)};
		}
		return Ui.bounds(name);
	}

	/** Focuses the element named {@code name} as the keyboard would, which scrolls it into view; false when there is none. */
	public static boolean reveal(String name) {
		Object screen = Minecraft.getInstance().screen;
		if (screen instanceof KitScreen kit) {
			Node node = node(kit, name);
			if (node != null) {
				kit.focusFromKeyboard(node);
				return true;
			}
		}
		return false;
	}

	/** The names of every shown element of a kit screen, for writing tours. */
	public static String names() {
		Object screen = Minecraft.getInstance().screen;
		if (!(screen instanceof KitScreen kit)) {
			return "";
		}
		List<Node> all = new ArrayList<>();
		walk(kit.root(), all);
		StringBuilder out = new StringBuilder();
		for (Node n : all) {
			out.append(" | '").append(n.accessibleName()).append('\'');
		}
		return out.toString();
	}

	private static Node node(KitScreen kit, String name) {
		String wanted = name.toLowerCase(Locale.ROOT);
		List<Node> all = new ArrayList<>();
		walk(kit.root(), all);
		for (int pass = 0; pass < 2; pass++) {
			for (Node n : all) {
				String label = n.accessibleName().toLowerCase(Locale.ROOT);
				if (pass == 0 ? label.equals(wanted) : label.startsWith(wanted)) {
					return n;
				}
			}
		}
		return null;
	}

	private static void walk(Node node, List<Node> out) {
		if (!node.visible()) {
			return;
		}
		if (node.hasName()) {
			out.add(node);
		}
		for (int i = 0; i < node.childCount(); i++) {
			walk(node.child(i), out);
		}
	}

	/** The particles alive now, as F3 counts them. */
	public static String particles() {
		return net.minecraft.client.Minecraft.getInstance().particleManager.getDebugInfo();
	}

	/** Minecraft's Resource Packs screen, over the current one. */
	public static void openPacks() {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		mc.openScreen(new net.minecraft.client.gui.screen.menu.resourcepacks.ResourcePacksScreen(mc.screen));
	}

	/** Singleplayer's world list, over the current screen. */
	public static void openWorlds() {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		mc.openScreen(new net.minecraft.client.gui.screen.menu.world.SelectWorldScreen(mc.screen));
	}

	/** A file put into the game folder as a player would. */
	public static String drop(String from, String to) throws java.io.IOException {
		java.nio.file.Path target = net.minecraft.client.Minecraft.getInstance().gameDir.toPath().resolve(to);
		java.nio.file.Files.createDirectories(target.getParent());
		java.nio.file.Files.copy(java.nio.file.Path.of(from), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return target.toString();
	}

	/** What a folder in the game folder holds. */
	public static String files(String folder) throws java.io.IOException {
		java.nio.file.Path dir = net.minecraft.client.Minecraft.getInstance().gameDir.toPath().resolve(folder);
		if (!java.nio.file.Files.isDirectory(dir)) {
			return "(none)";
		}
		try (java.util.stream.Stream<java.nio.file.Path> list = java.nio.file.Files.list(dir)) {
			return list.map(p -> p.getFileName().toString()).sorted().toList().toString();
		}
	}

	public static String enable(String id, String on) {
		Module m = Flint.modules().byId(id);
		if (m == null) {
			return "no mod " + id;
		}
		m.setEnabled(Boolean.parseBoolean(on));
		return m.name + (m.enabled() ? " on" : " off");
	}

	@SuppressWarnings("unchecked")
	public static String set(String id, String setting, String value) {
		Module m = Flint.modules().byId(id);
		if (m == null) {
			return "no mod " + id;
		}
		for (Setting<?> s : m.settings()) {
			if (s.id.equals(setting)) {
				if (s instanceof Setting.Bool) {
					((Setting.Bool) s).set(Boolean.parseBoolean(value));
				} else if (s instanceof Setting.Int || s instanceof Setting.Key) {
					((Setting<Integer>) s).set(Integer.parseInt(value));
				} else if (s instanceof Setting.Color) {
					String hex = value.replace("#", "");
					long v = Long.parseLong(hex, 16);
					((Setting.Color) s).set(hex.length() <= 6 ? (int) v | 0xFF000000 : (int) v);
				} else if (s instanceof Setting.Choice) {
					Setting.Choice c = (Setting.Choice) s;
					int was = c.index();
					int found = -1;
					for (int i = 0; i < c.options.length && found < 0; i++) {
						c.set(i);
						if (c.key().equalsIgnoreCase(value) || c.options[i].equalsIgnoreCase(value)) {
							found = i;
						}
					}
					c.set(found >= 0 ? found : was);
					if (found < 0) {
						return "no option " + value + " in " + setting;
					}
				}
				return m.name + ": " + s.label + " = " + s.get();
			}
		}
		return "no setting " + setting + " in " + id;
	}
}
