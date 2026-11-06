package com.flintclient.legacy.shared;

import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.rshift.Catalog;
import com.flintclient.client.platform.MinecraftOptions;
import com.flintclient.legacy.Modules;
import com.flintclient.legacy.module.Module;

/**
 * What Minecraft 1.8.9 runs, for the shared Right Shift catalog ({@link Catalog.Engine}): the mods that have a 1.8.9 backend
 * ({@code legacy/mods}), and of each only the settings that backend honours; of Flint's client settings, those 1.8.9's General
 * settings bind; of Minecraft's video options, those 1.8.9 has. Mods without a 1.8.9 backend are hidden, as the owner decided
 * (docs/LEGACY189_SHARED_MENU_PLAN.md), not shown as coming soon.
 */
public final class Engine189 implements Catalog.Engine {
	private final Modules modules;

	public Engine189(Modules modules) {
		this.modules = modules;
	}

	@Override
	public boolean runs(ModuleDescriptor module) {
		return modules.byId(module.id()) != null;
	}

	@Override
	public boolean shows(Catalog.Row row) {
		if (row.module() != null) {
			Module backend = modules.byId(row.module().id());
			if (backend == null) {
				return false;
			}
			// A switch or a link to the mod; otherwise one of its settings or its HUD look fields, if the backend honours it.
			return row.setting() == null || backend.honours(row.setting());
		}
		if (row.look()) {
			// The profile's own HUD look: 1.8.9 draws the shared HUD, which takes it as every line does.
			return true;
		}
		if (row.global()) {
			if (row.setting() == null) {
				// A button (open the screenshots or resource packs folder).
				return true;
			}
			if (row.minecraft()) {
				return MinecraftOptions.has(row.setting());
			}
			return modules.general.bound(row.setting()) != null;
		}
		return false;
	}
}
