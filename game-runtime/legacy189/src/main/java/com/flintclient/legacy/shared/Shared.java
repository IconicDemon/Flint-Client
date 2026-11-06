package com.flintclient.legacy.shared;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.Layout;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.store.ControlSettings;
import com.flintclient.client.control.store.LayoutStore;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Flint's shared settings on 1.8.9: the same files as on every other Minecraft line, in the profile's config folder:
 * {@code config/flintclient/control-center.json} (Flint's client settings, favourites, the active profile) and
 * {@code config/flintclient/layouts/*.json} (each profile's mods: on or off, settings, HUD placements, look). The 1.8.9 mods
 * are their backends: they read and write the active layout's module states. An older 1.8.9 settings file
 * ({@code legacy189.json}) is carried over once, first ({@link Migration189}).
 */
public final class Shared {
	private static final Logger LOG = LogManager.getLogger("Flint");
	private static boolean ready;

	private Shared() {
	}

	/** Reads the stores (once, at start-up) after carrying over a 1.8.9 settings file from before. */
	public static void init(Path configDir) {
		// The same settings and layouts the shared menu reads (ControlState), so there is one copy of every value.
		ControlState.init(configDir);
		ready = true;
		Migration189.Result migrated = Migration189.migrateFile(configDir, ControlState.layouts().active(), ControlState.settings());
		if (migrated != null) {
			// The old file is moved aside only once the new files are written: until then the next start carries it over again.
			if (save() && migrated.backUp()) {
				LOG.info("Flint carried its 1.8.9 settings over to the shared settings ({} mods); the old file is kept as {}", migrated.modules(),
					migrated.backup().getFileName());
			} else {
				LOG.warn("Flint couldn't finish carrying its 1.8.9 settings over; it will try again next time");
			}
		}
	}

	public static boolean ready() {
		return ready;
	}

	public static ControlSettings settings() {
		return ControlState.settings();
	}

	/** The layout (profile) in use. */
	public static Layout layout() {
		return ControlState.layout();
	}

	/** A module's state in the layout in use, by its registry index ({@link ModuleRegistry#indexOf}). */
	public static ModuleState state(int index) {
		return ControlState.layout().modules().get(index);
	}

	/** Writes whatever changed (a no-op when nothing did); false when something couldn't be written. */
	public static boolean save() {
		if (!ready) {
			return true;
		}
		boolean ok = true;
		ControlSettings settings = ControlState.settings();
		if (settings.dirty()) {
			ok &= settings.save();
		}
		LayoutStore layouts = ControlState.layouts();
		if (layouts.dirty()) {
			ok &= layouts.save();
		}
		return ok;
	}
}
