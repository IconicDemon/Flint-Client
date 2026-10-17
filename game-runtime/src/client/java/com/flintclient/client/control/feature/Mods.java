package com.flintclient.client.control.feature;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;

/**
 * Reading a module's state quickly from the hooks: by its registry index (looked up once), on the profile in use.
 * Allocation-free.
 */
public final class Mods {
	private Mods() {
	}

	public static int index(String id) {
		int index = ModuleRegistry.indexOf(id);
		if (index < 0) {
			throw new IllegalArgumentException("no module " + id);
		}
		return index;
	}

	public static ModuleState state(int index) {
		return ControlState.layout().modules().get(index);
	}

	/** On, and built (a module that isn't built never acts, whatever a file says). */
	public static boolean on(int index) {
		ModuleState state = state(index);
		return state.enabled() && state.descriptor().status().usable();
	}
}
