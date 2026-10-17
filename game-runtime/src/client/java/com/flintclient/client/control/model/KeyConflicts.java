package com.flintclient.client.control.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Which other Flint key settings share a key, for the warning under a key setting. Only modules that are on count (an
 * off module's key does nothing). Version-neutral; Minecraft's own mappings are added by the platform.
 */
public final class KeyConflicts {
	private KeyConflicts() {
	}

	/**
	 * The other key settings in {@code layout} bound to {@code key}, as "Module: setting", leaving out the setting
	 * {@code settingId} of {@code moduleId} itself; empty for no key.
	 */
	public static List<String> flintUses(Layout layout, String key, String moduleId, String settingId) {
		List<String> out = new ArrayList<>();
		if (key.isEmpty()) {
			return out;
		}
		for (ModuleState module : layout.modules()) {
			if (!module.enabled()) {
				continue;
			}
			for (SettingDescriptor<?> setting : module.descriptor().settings()) {
				if (setting instanceof KeySetting other && !(module.id().equals(moduleId) && other.id().equals(settingId))
					&& key.equals(module.text(other))) {
					out.add(module.descriptor().name() + ": " + other.label());
				}
			}
		}
		return out;
	}

	/** The warning under a key setting: "Also used by A and B", or null when nothing else uses it. */
	public static String warning(List<String> uses) {
		if (uses.isEmpty()) {
			return null;
		}
		StringBuilder text = new StringBuilder("Also used by ");
		for (int i = 0; i < uses.size(); i++) {
			if (i > 0) {
				text.append(i == uses.size() - 1 ? " and " : ", ");
			}
			text.append(uses.get(i));
		}
		return text.toString();
	}
}
