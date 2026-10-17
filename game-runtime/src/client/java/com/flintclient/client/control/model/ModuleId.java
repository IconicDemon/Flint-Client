package com.flintclient.client.control.model;

import java.util.List;

/**
 * The ids of Flint's original HUD modules (the others keep theirs on their own class in {@code model.mods}). They are the keys in layout files and the library's favourites and recents,
 * so they never change once released.
 */
public final class ModuleId {
	public static final String FPS = "fps";
	public static final String PING = "ping";
	public static final String CPS = "cps";
	public static final String KEYSTROKES = "keystrokes";
	public static final String COORDINATES = "coordinates";
	public static final String DIRECTION = "direction";
	public static final String ARMOR = "armor";
	public static final String EFFECTS = "effects";

	/** The eight original HUD modules' ids, in registry order. */
	public static final List<String> CORE = List.of(FPS, PING, CPS, KEYSTROKES, COORDINATES, DIRECTION, ARMOR, EFFECTS);

	/** Every module's id, in registry order. */
	public static final List<String> ALL = ModuleRegistry.all().stream().map(ModuleDescriptor::id).toList();

	private ModuleId() {
	}
}
