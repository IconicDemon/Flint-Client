package com.flintclient.qa.legacy;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.menu.resourcepacks.ResourcePacksScreen;
import net.minecraft.client.resource.pack.ResourcePacks;

/**
 * Dev-only QA helpers for resource packs the Flint app adds while 1.8.9 runs (game-runtime legacy189 LivePacks189), called
 * from tours with {@code call com.flintclient.qa.legacy.QaLivePacks.METHOD}.
 */
public final class QaLivePacks {
	private QaLivePacks() {
	}

	/** The packs 1.8.9 has selected (bottom first), the ones it lists, and what options.txt keeps. */
	public static String packs() {
		Minecraft mc = Minecraft.getInstance();
		ResourcePacks repository = mc.getResourcePacks();
		return "selected=" + names(repository.getSelectedPacks()) + " available=" + names(repository.getAvailablePacks()) + " options="
			+ mc.options.resourcePacks + " incompatible=" + mc.options.incompatibleResourcePacks;
	}

	private static List<String> names(List<?> packs) {
		List<String> out = new ArrayList<String>();
		for (Object pack : packs) {
			out.add(((ResourcePacks.UnopenedPack) pack).getName());
		}
		return out;
	}

	/** Minecraft's Resource Packs screen, as Options opens it. */
	public static String openPacks() {
		Minecraft mc = Minecraft.getInstance();
		mc.openScreen(new ResourcePacksScreen(mc.screen));
		return "opened";
	}

	/** Back to the game. */
	public static String close() {
		Minecraft.getInstance().openScreen(null);
		return "closed";
	}
}
