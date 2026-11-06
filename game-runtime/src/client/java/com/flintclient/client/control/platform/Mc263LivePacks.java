package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.store.PackHandoff;
import com.flintclient.client.files.PackFixer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.GenericWaitingScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import org.jspecify.annotations.Nullable;

/**
 * Packs the Flint app put into this profile while the game runs ({@link PackHandoff}: {@code .flint-packs.json}, looked at once
 * a second), shown in the game at once, on the game's thread and only when it is safe: never while resources reload or a world
 * loads (the change waits until it's done).
 *
 * <ul>
 * <li>A resource pack: the pack list is read again, so it shows in Options → Resource Packs at once (that screen, when open,
 * watches its folder itself). Asked to turn it on, it goes on top of the selected packs, the options are saved and resources
 * reload, exactly what Done on that screen does; one made for another version stays listed but off. While the Resource Packs
 * screen is open, turning it on waits until the screen closes (its own Done would otherwise undo it).
 * <li>A shader pack, with Iris: Iris's list is refreshed when its screen is open (it reads the folder whenever it opens), and
 * asked to turn it on, the pack is selected and applied the way Iris's own Apply does ({@link IrisPacks}). Without Iris, a
 * notice says shaders need it. While Iris's screen is open, turning it on waits until it closes.
 * <li>A data pack: a notice says to run /reload in that world or rejoin it (data packs load with the world).
 * <li>A pack removed: it leaves the list; one that was on is turned off (resources reload; shaders off).
 * </ul>
 *
 * Each change shows a short notice ({@link Mc263NoticeToast}). Fault-isolated: a failure turns this off for the session, never
 * the game. Render thread.
 */
public final class Mc263LivePacks {
	private static final int LOOK_TICKS = 20;
	/** At most this many changes wait (a burst beyond it is the launcher's own limit anyway). */
	private static final int MAX_WAITING = 64;
	static final HookGuard GUARD = new HookGuard("live packs",
		(path, error) -> FlintClient.LOGGER.warn("Flint: packs added while playing can't be shown on this Minecraft; they're off for this session", error));

	/** A change waiting to be applied; {@code told}: its notice was already shown (turning it on waits for a screen). */
	private static final class Waiting {
		final PackHandoff.Change change;
		boolean told;

		Waiting(PackHandoff.Change change) {
			this.change = change;
		}
	}

	private static final ArrayDeque<Waiting> WAITING = new ArrayDeque<>();
	/** Notices about a resource reload just started: shown once it is done and the game is in view again. */
	private static final List<String> AFTER_RELOAD = new ArrayList<>();
	private static @Nullable PackHandoff handoff;
	private static int ticks;

	private Mc263LivePacks() {
	}

	/** Every client tick. */
	public static void tick() {
		if (!GUARD.on()) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
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
			if (!AFTER_RELOAD.isEmpty() && mc.gui.overlay() == null) {
				AFTER_RELOAD.forEach(Mc263LivePacks::say);
				AFTER_RELOAD.clear();
			}
			if (!WAITING.isEmpty() && safe(mc)) {
				apply(mc, h.gameDir());
			}
		} catch (RuntimeException | LinkageError e) {
			WAITING.clear();
			GUARD.fail(e);
		}
	}

	/** When this game started (ms since the epoch): the launcher's changes from before are for an earlier game. */
	static long startedAt() {
		try {
			return java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
		} catch (RuntimeException | LinkageError e) {
			return System.currentTimeMillis();
		}
	}

	/** Whether the game can take a pack now: no resource reload under way, no world loading or connecting. */
	static boolean safe(Minecraft mc) {
		if (mc.gui.overlay() != null) {
			return false;
		}
		Screen screen = mc.gui.screen();
		if (screen instanceof LevelLoadingScreen || screen instanceof ProgressScreen || screen instanceof ConnectScreen
			|| screen instanceof GenericMessageScreen || screen instanceof GenericWaitingScreen) {
			return false;
		}
		if (mc.level == null) {
			// A world being started has its server before its level.
			return mc.getSingleplayerServer() == null;
		}
		return mc.player != null;
	}

	private static void apply(Minecraft mc, Path gameDir) {
		Screen screen = mc.gui.screen();
		boolean packScreen = screen != null && Mc263GameFolders.kindShown(screen) == PackFixer.Kind.RESOURCE_PACKS;
		boolean shaderScreen = Mc263GameFolders.isShaderScreen(screen);
		List<Waiting> resource = new ArrayList<>();
		List<Waiting> shader = new ArrayList<>();
		for (Waiting w : WAITING) {
			switch (w.change.kind()) {
				case RESOURCE_PACK -> resource.add(w);
				case SHADER_PACK -> shader.add(w);
				case DATA_PACK -> dataPack(w.change);
			}
		}
		WAITING.clear();
		if (!resource.isEmpty()) {
			resourcePacks(mc, resource, packScreen);
		}
		if (!shader.isEmpty()) {
			shaderPacks(mc, gameDir, shader, shaderScreen);
		}
	}

	// ---------- resource packs ----------

	private static void resourcePacks(Minecraft mc, List<Waiting> changes, boolean screenOpen) {
		if (screenOpen) {
			// The open Resource Packs screen lists the folder itself (it watches it) and would undo a selection made under it
			// when Done is pressed: notices now, turning on once it has closed.
			for (Waiting w : changes) {
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
		PackRepository repository = mc.getResourcePackRepository();
		List<String> before = new ArrayList<>(repository.getSelectedIds());
		repository.reload();
		// A pack removed while it was on (where the system let the file go) left the selection with the reload.
		boolean changed = !before.equals(new ArrayList<>(repository.getSelectedIds()));
		List<String> turnedOn = new ArrayList<>();
		for (Waiting w : changes) {
			PackHandoff.Change change = w.change;
			String id = "file/" + change.file();
			if (change.removed()) {
				if (!w.told) {
					say(change.title() + " removed");
				}
				continue;
			}
			Pack pack = repository.getPack(id);
			if (pack == null) {
				say(change.title() + " isn't a resource pack Minecraft can read");
				continue;
			}
			if (!change.enable()) {
				if (!w.told) {
					say(change.title() + " added to your resource packs");
				}
				continue;
			}
			if (!pack.getCompatibility().isCompatible()) {
				// Listed (marked incompatible, as Minecraft marks it), but never forced on.
				say(change.title() + " is for another version, so it's off");
				continue;
			}
			changed |= repository.addPack(id);
			turnedOn.add(change.title());
		}
		if (changed) {
			// What Done on the Resource Packs screen does: the list saved in the options, resources reloaded if it changed.
			mc.options.updateResourcePacks(repository);
		}
		for (String title : turnedOn) {
			AFTER_RELOAD.add(title + " turned on");
		}
	}

	// ---------- shader packs ----------

	private static void shaderPacks(Minecraft mc, Path gameDir, List<Waiting> changes, boolean screenOpen) {
		if (!IrisPacks.present()) {
			for (Waiting w : changes) {
				if (!w.change.removed()) {
					say(w.change.title() + " added; shaders need Iris (add it in Flint, then restart)");
				}
			}
			return;
		}
		if (screenOpen) {
			try {
				Mc263GameFolders.refreshShaderList(mc.gui.screen());
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				FlintClient.LOGGER.debug("Flint: couldn't bring Iris's shader pack list up to date; it is when opened again", e);
			}
			for (Waiting w : changes) {
				if (!w.told) {
					w.told = true;
					say(w.change.title() + (w.change.removed() ? " removed" : " added to your shader packs"));
				}
				if (w.change.enable()) {
					WAITING.addLast(w);
				}
			}
			return;
		}
		// Only one shader pack can be on: the newest asked for.
		Waiting chosen = null;
		for (Waiting w : changes) {
			if (w.change.enable()) {
				chosen = w;
			}
		}
		for (Waiting w : changes) {
			PackHandoff.Change change = w.change;
			if (change.removed()) {
				boolean wasOn = change.file().equals(IrisPacks.selected()) && IrisPacks.enabled();
				if (wasOn && !Files.exists(gameDir.resolve("shaderpacks").resolve(change.file()))) {
					IrisPacks.turnOff();
					say(change.title() + " removed; shaders are off");
				} else if (!w.told) {
					say(change.title() + " removed");
				}
				continue;
			}
			if (w != chosen) {
				if (!w.told) {
					say(change.title() + " added to your shader packs");
				}
				continue;
			}
			Path path = PackHandoff.path(gameDir, change);
			switch (path == null ? IrisPacks.Result.INVALID : IrisPacks.select(change.file())) {
				case ON -> say(change.title() + " turned on");
				case INVALID -> say(change.title() + " isn't a shader pack Iris can load");
				case FAILED -> say("Couldn't turn on " + change.title() + "; pick it in Video Settings → Shader Packs");
			}
		}
	}

	// ---------- data packs ----------

	private static void dataPack(PackHandoff.Change change) {
		say(change.removed() ? change.title() + " removed from " + change.world()
			: change.title() + " added to " + change.world() + ": /reload or rejoin to load it");
	}

	private static void say(String text) {
		Mc263NoticeToast.show(text);
		FlintClient.LOGGER.info("Flint: {}", text);
	}
}
