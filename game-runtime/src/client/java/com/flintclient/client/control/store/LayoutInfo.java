package com.flintclient.client.control.store;

import com.flintclient.client.control.model.HudThemeId;
import org.jspecify.annotations.Nullable;

/**
 * One row of the Layouts page.
 *
 * @param id the file id
 * @param name the display name (the id when the file couldn't be read)
 * @param modifiedMillis when the file was last written, or -1 when it hasn't been yet
 * @param enabledModules how many modules it shows
 * @param theme its shared HUD style
 * @param active whether it is the layout in use
 * @param damaged the file couldn't be read; the row can only be deleted (a copy of the file was kept)
 * @param readOnly changes won't be saved (a newer Flint wrote it)
 * @param notice what to tell the player about the file, or null
 */
public record LayoutInfo(String id, String name, long modifiedMillis, int enabledModules, HudThemeId theme, boolean active,
	boolean damaged, boolean readOnly, @Nullable String notice) {
}
