package com.flintclient.legacy.module;

import com.flintclient.client.control.model.SettingDescriptor;

/**
 * The 1.8.9 backend of a HUD module: the module draws through the shared HUD ({@code control.hud.render}), exactly as on every
 * Minecraft line, so the backend only feeds it what 1.8.9 knows (through {@code platform.HudData} and friends). The shared
 * view honours every setting of its module and of the HUD look, so the menu shows them all, less those 1.8.9 can't have
 * (named by the start of their ids: effect bars, totems...), which wait out of sight with their values kept.
 */
public abstract class HudBackend extends Module {
	private final String[] missing;

	protected HudBackend(String id, String picture, String... missing) {
		super(id, picture);
		this.missing = missing;
	}

	@Override
	public boolean honours(SettingDescriptor<?> shared) {
		String id = shared.id();
		for (String prefix : missing) {
			if (id.startsWith(prefix)) {
				return false;
			}
		}
		return true;
	}
}
