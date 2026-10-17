package com.flintclient.client.control.model;

import java.util.List;

/**
 * A titled group of settings in a module's panel, in display order. An {@code advanced} section is shown collapsed
 * under "Advanced" (progressive disclosure), so the default view stays short.
 */
public record SettingSection(String title, List<SettingDescriptor<?>> settings, boolean advanced) {
	public SettingSection {
		settings = List.copyOf(settings);
	}

	public static SettingSection of(String title, SettingDescriptor<?>... settings) {
		return new SettingSection(title, List.of(settings), false);
	}

	/** A section shown collapsed under "Advanced". */
	public static SettingSection advanced(String title, SettingDescriptor<?>... settings) {
		return new SettingSection(title, List.of(settings), true);
	}
}
