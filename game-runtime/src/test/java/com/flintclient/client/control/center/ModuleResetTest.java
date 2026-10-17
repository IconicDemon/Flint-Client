package com.flintclient.client.control.center;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flintclient.client.control.hud.HudPlacement;
import com.flintclient.client.control.model.Appearance;
import com.flintclient.client.control.model.HudColor;
import com.flintclient.client.control.model.HudThemeId;
import com.flintclient.client.control.model.ModuleDescriptor;
import com.flintclient.client.control.model.ModuleId;
import com.flintclient.client.control.model.ModuleRegistry;
import com.flintclient.client.control.model.ModuleState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleResetTest {
	@Test
	void slidersShowAsManyDecimalsAsTheirStep() {
		assertEquals(0, SettingControls.decimals(1.0F));
		assertEquals(1, SettingControls.decimals(0.5F));
		assertEquals(2, SettingControls.decimals(0.05F));
		assertEquals(3, SettingControls.decimals(0.0001F));
	}

	@Test
	void everyModuleSettingBuildsARow() {
		List<Runnable> syncs = new ArrayList<>();
		int settings = 0;
		for (ModuleDescriptor module : ModuleRegistry.all()) {
			ModuleState state = new ModuleState(module);
			for (var setting : module.settings()) {
				SettingControls.row(state, setting, changed -> { }, syncs);
				settings++;
			}
		}
		for (Runnable sync : syncs) {
			sync.run();
		}
		assertTrue(syncs.size() >= settings);
	}
}
