package com.flintclient.legacy;

import com.flintclient.client.control.platform.HookGuard;
import com.flintclient.client.control.store.PackHandoff;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.menu.multiplayer.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.menu.resourcepacks.ResourcePacksScreen;
import net.minecraft.client.resource.pack.ResourcePacks;
import org.jspecify.annotations.Nullable;

/**
 * Resource packs the Flint app put into this profile while 1.8.9 runs ({@link PackHandoff}: {@code .flint-packs.json}, looked
 * at once a second), shown in the game at once, as on every line. The pack list is read again (1.8.9's {@code ResourcePacks}),
 * so it shows in Options → Resource Packs; asked to turn it on, it goes on top of the selected packs, the options are saved and
 * resources reload, exactly what Done on that screen does. A pack of another pack format (1.8.9's is 1) stays listed but off.
 * While the Resource Packs screen is open it is brought up to date (unless the player already moved packs around there), and
 * turning on waits until it closes. 1.8.9 has no shader packs (Flint's 1.8.9 has no shader loader, and the app doesn't offer
 * them) and no data packs: such changes are only logged.
 *
 * <p>Notices are the same cards Flint shows for friends ({@link FriendNotices189}). Never while connecting to a server or
 * loading its terrain (1.8.9 loads a singleplayer world and reloads resources on the game's thread, so this can't run during
 * those). Fault-isolated: a failure turns this off for the session, never the game. Game thread.
 */
final class LivePacks189 {
	private static final int LOOK_TICKS = 20;
	private static final int MAX_WAITING = 64;
	/** 1.8.9's resource pack format: packs of another one are marked incompatible in Minecraft's own screen. */
	private static final int PACK_FORMAT = 1;
	private static final HookGuard GUARD = new HookGuard("live packs",
		(path, error) -> Flint.LOG.warn("Flint: packs added while playing can't be shown on this Minecraft; they're off for this session", error));

	/** A change waiting to be applied; {@code told}: its notice was already shown (turning it on waits for the screen). */
	private static final class Waiting {
		final PackHandoff.Change change;
		boolean told;

		Waiting(PackHandoff.Change change) {
			this.change = change;
		}
	}

	private static final ArrayDeque<Waiting> WAITING = new ArrayDeque<>();
	private static @Nullable PackHandoff handoff;
	private static int ticks;

	private LivePacks189() {
	}

	/** Every client tick. */
	static void tick(Minecraft mc) {
		if (!GUARD.on()) {
			return;
		}
		try {
			PackHandoff h = handoff;
			if (h == null) {
				h = handoff = new PackHandoff(FabricLoader.getInstance().getGameDir(), startedAt());
			}
			if (ticks++ % LOOK_TICKS == 0) {
				for (PackHandoff.Change change : h.poll()) {
					if (WAITING.size() >= MAX_WAITING) {
						WAITING.removeFirst();
					}
					WAITING.addLast(new Waiting(change));
				}
			}
			if (!WAITING.isEmpty() && safe(mc)) {
				apply(mc);
			}
		} catch (RuntimeException | LinkageError e) {
			WAITING.clear();
			GUARD.fail(e);
		}
	}

	private static long startedAt() {
		try {
			return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
		} catch (RuntimeException | LinkageError e) {
			return System.currentTimeMillis();
		}
	}

	/** Whether the game can take a pack now: not connecting to a server or loading its terrain. */
	private static boolean safe(Minecraft mc) {
		Screen screen = mc.screen;
		if (screen instanceof ConnectScreen || screen instanceof DownloadingTerrainScreen || screen instanceof ProgressScreen) {
			return false;
		}
		return mc.world == null || mc.player != null;
	}

	private static void apply(Minecraft mc) {
		List<Waiting> packs = new ArrayList<>();
		for (Waiting w : WAITING) {
			if (w.change.kind() == PackHandoff.Kind.RESOURCE_PACK) {
				packs.add(w);
			} else {
				Flint.LOG.info("Flint: {} {} isn't something Minecraft 1.8.9 has; left as it is", w.change.kind(), w.change.file());
			}
		}
		WAITING.clear();
		if (packs.isEmpty()) {
			return;
		}
		Screen screen = mc.screen;
		if (screen instanceof ResourcePacksScreen) {
			// Its init reads the folder again, unless the player already moved packs around there (that would be lost); turning
			// on waits until it closes, as its own Done would undo a selection made under it.
			if (!GameFolders.changedByPlayer(screen)) {
				screen.init(mc, screen.width, screen.height);
			}
			for (Waiting w : packs) {
				if (!w.told) {
					w.told = true;
					say(w.change.title() + (w.change.removed() ? " removed" : " added to your resource packs"));
				}
				if (w.change.enable()) {
					WAITING.addLast(w);
				}
			}
			return;
		}
		ResourcePacks repository = mc.getResourcePacks();
		repository.reload();
		List<ResourcePacks.UnopenedPack> selected = new ArrayList<>();
		for (Object pack : repository.getSelectedPacks()) {
			selected.add((ResourcePacks.UnopenedPack) pack);
		}
		// A pack removed while it was on (where the system let the file go) leaves the selection; 1.8.9's reload keeps it there.
		boolean changed = selected.removeIf(p -> !file(repository, p.getName()).exists());
		List<String> turnedOn = new ArrayList<>();
		for (Waiting w : packs) {
			PackHandoff.Change change = w.change;
			if (change.removed()) {
				if (!w.told) {
					say(change.title() + " removed");
				}
				continue;
			}
			ResourcePacks.UnopenedPack pack = available(repository, change.file());
			if (pack == null) {
				say(change.title() + " isn't a resource pack Minecraft can read");
			} else if (!change.enable()) {
				if (!w.told) {
					say(change.title() + " added to your resource packs");
				}
			} else if (pack.getFormat() != PACK_FORMAT) {
				// Listed (Minecraft marks it incompatible), but never forced on.
				say(change.title() + " is for another version, so it's off");
			} else {
				// On top: the selection's last pack is the top one.
				if (selected.isEmpty() || !selected.get(selected.size() - 1).equals(pack)) {
					selected.remove(pack);
					selected.add(pack);
					changed = true;
				}
				turnedOn.add(change.title());
			}
		}
		if (changed) {
			// What Done on the Resource Packs screen does.
			repository.setSelectedPacks(selected);
			mc.options.resourcePacks.clear();
			mc.options.incompatibleResourcePacks.clear();
			for (ResourcePacks.UnopenedPack pack : selected) {
				mc.options.resourcePacks.add(pack.getName());
				if (pack.getFormat() != PACK_FORMAT) {
					mc.options.incompatibleResourcePacks.add(pack.getName());
				}
			}
			mc.options.save();
			mc.reloadResources();
		}
		for (String title : turnedOn) {
			say(title + " turned on");
		}
	}

	/** A notice card (as Flint's friend notices), and the same line in the log. */
	private static void say(String text) {
		FriendNotices189.show(text);
		Flint.LOG.info("Flint: {}", text);
	}

	private static File file(ResourcePacks repository, String name) {
		return new File(repository.getDirectory(), name);
	}

	private static ResourcePacks.@Nullable UnopenedPack available(ResourcePacks repository, String name) {
		for (Object o : repository.getAvailablePacks()) {
			ResourcePacks.UnopenedPack pack = (ResourcePacks.UnopenedPack) o;
			if (pack.getName().equals(name)) {
				return pack;
			}
		}
		return null;
	}
}
