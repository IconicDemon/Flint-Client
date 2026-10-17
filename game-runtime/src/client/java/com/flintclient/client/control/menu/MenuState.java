package com.flintclient.client.control.menu;

import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.model.ModuleState;
import org.jspecify.annotations.Nullable;

/**
 * The menu's own state for this game session: the header tab, the chip, the search, the scroll position and the open
 * settings page (in memory: it survives closing and reopening the menu, and resets when the game restarts). A card's
 * Enabled state is its module's own, kept in the active layout; favourites are kept in Flint's settings. Render thread.
 */
final class MenuState {
	/** The header's sections. Only MODS has content in this phase; the others just take the selection. */
	enum Tab {
		MODS,
		SETTINGS,
		PROFILES,
		PRESETS,
		WAYPOINTS,
		/** Opened by the brush in the header, not a header tab. */
		THEMES
	}

	private static MenuState session;

	Tab tab = Tab.MODS;
	MenuCatalog.Category category = MenuCatalog.Category.ALL;
	String query = "";
	/** Only favourite modules show in the grid (the star button by the chips). */
	boolean favoritesOnly;
	float scroll;
	/** The module whose settings page is open (null: the grid), and whether its preview shows. */
	@Nullable String page;
	boolean previewOn = true;
	private MenuState() {
	}

	static MenuState session() {
		if (session == null) {
			session = new MenuState();
		}
		return session;
	}

	/** A card's module, on the layout in use now. */
	static ModuleState module(int index) {
		return ControlState.layout().module(MenuCatalog.moduleOf(MenuCatalog.ENTRIES.get(index)));
	}

	/** A card's Enabled state: its module's, kept in the active layout. */
	boolean enabled(int index) {
		return module(index).enabled();
	}

	/** Whether a card's module can be turned on (it is built). */
	boolean usable(int index) {
		return module(index).descriptor().status().usable();
	}

	/** Flips a card's module on or off, saved with the layout; a module that isn't built stays off. */
	void toggleEnabled(int index) {
		ModuleState state = module(index);
		if (!state.descriptor().status().usable()) {
			return;
		}
		if (state.setEnabled(!state.enabled())) {
			ControlState.settings().recordChange(state.id());
		}
	}

	/** Favourites are kept in Flint's settings, so they survive a restart and are the same in every profile. */
	boolean favorite(int index) {
		return ControlState.settings().isFavorite(MenuCatalog.moduleOf(MenuCatalog.ENTRIES.get(index)));
	}

	void toggleFavorite(int index) {
		ControlState.settings().toggleFavorite(MenuCatalog.moduleOf(MenuCatalog.ENTRIES.get(index)));
	}
}
