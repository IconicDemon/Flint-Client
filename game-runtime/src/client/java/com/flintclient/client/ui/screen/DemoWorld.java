package com.flintclient.client.ui.screen;

import com.flintclient.client.FlintClient;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Demo accounts play Minecraft's demo world, exactly as the vanilla title screen starts it. */
final class DemoWorld {
	private static final String LEVEL_ID = "Demo_World";

	private DemoWorld() {
	}

	static void play(Minecraft minecraft, Screen menu) {
		boolean present;
		try (LevelStorageSource.LevelStorageAccess access = minecraft.getLevelSource().createAccess(LEVEL_ID)) {
			present = access.hasWorldData();
		} catch (IOException e) {
			SystemToast.onWorldAccessFailure(minecraft, LEVEL_ID);
			FlintClient.LOGGER.warn("Failed to read demo world data", e);
			return;
		}
		if (present) {
			minecraft.createWorldOpenFlows().openWorld(LEVEL_ID, () -> minecraft.gui.setScreen(menu));
		} else {
			minecraft.createWorldOpenFlows()
				.createFreshLevel(LEVEL_ID, MinecraftServer.DEMO_SETTINGS, WorldOptions.DEMO_OPTIONS, WorldPresets::createNormalWorldDimensions, menu);
		}
	}
}
