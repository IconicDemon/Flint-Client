package com.flintclient.client.control.model;

import org.jspecify.annotations.Nullable;

/** A choice from Flint's fixed text palette, shown as swatches rather than a segmented control. */
public final class ColorSetting extends ChoiceSetting<HudColor> {
	public ColorSetting(String id, String label, @Nullable String description, HudColor defaultValue) {
		super(id, label, description, HudColor.class, defaultValue);
	}
}
