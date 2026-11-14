package com.flintclient.client.control.rshift;

import com.flintclient.client.module.social.InstantSwap;
import com.flintclient.client.platform.MenuHost;
import com.flintclient.client.ui.kit.Node;
import org.jspecify.annotations.Nullable;

/**
 * The in-game Library, a window of its own (as Discover and Cosmetics are), opened from the Right Shift menu's Library tile
 * and the main menu's Utilities: mods, resource packs and shaders for this game's profile, from Modrinth or CurseForge, through
 * the Flint app ({@link LibraryPage}). When it closes after a mod was added or taken out, Instant Swap has the game with the
 * new mods take over ({@link InstantSwap#libraryClosed}). Offered where {@link MenuHost#libraryOffered} says (every line with Instant Swap).
 */
public final class FlintLibraryScreen extends WindowScreen {
	private final LibraryPage page;
	/** A screen the page opened over this one (Minecraft's resource packs, Iris' shaders): the Library isn't closed then. */
	private boolean childOpen;

	public FlintLibraryScreen(@Nullable Object parent) {
		super("Library", parent, 1320, 820);
		this.page = new LibraryPage(this, next -> {
			childOpen = true;
			MenuHost.show(next);
		});
	}

	@Override
	protected Node content() {
		return page;
	}

	@Override
	protected void step() {
		page.step();
	}

	@Override
	protected void shown() {
		super.shown();
		childOpen = false;
	}

	@Override
	protected void hidden() {
		super.hidden();
		if (!childOpen && page.modsChanged()) {
			InstantSwap.libraryClosed();
		}
	}

	@Override
	protected void initialFocus(boolean keyboard) {
		// Typing searches at once.
		focus(page.searchField());
	}
}
