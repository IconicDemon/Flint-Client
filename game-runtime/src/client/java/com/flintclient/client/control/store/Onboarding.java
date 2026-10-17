package com.flintclient.client.control.store;

import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.OnboardingDecision;
import com.flintclient.client.control.model.Preset;

/**
 * What first-time setup does to the saved state. Skipping only records that setup is done. Finishing adds the
 * chosen preset as a new layout named after it (numbered if that name is taken), in the chosen style, and
 * switches to it; it never edits an existing layout.
 */
public final class Onboarding {
	private final ControlSettings settings;
	private final LayoutStore layouts;

	public Onboarding(ControlSettings settings, LayoutStore layouts) {
		this.settings = settings;
		this.layouts = layouts;
	}

	/** Whether to open setup by itself on this Control Center open (see {@link OnboardingDecision}). */
	public boolean shouldShow() {
		return OnboardingDecision.shouldShow(settings.onboardingCompleted(), layouts.anyLayoutFileExists());
	}

	/** "Skip setup": marks it done and changes nothing else. */
	public void skip() {
		settings.setOnboardingCompleted(true);
		settings.save();
	}

	/** "Done": creates the preset layout in {@code theme}, switches to it and marks setup done. */
	public LayoutResult finish(Preset preset, HudThemeId theme) {
		LayoutResult created = layouts.create(layouts.uniqueName(preset.label()), preset, theme);
		String id = created.id();
		if (id == null) {
			return created;
		}
		settings.setOnboardingCompleted(true);
		// Switching saves the settings (active layout and the finished flag) in one write.
		return layouts.switchTo(id);
	}
}
