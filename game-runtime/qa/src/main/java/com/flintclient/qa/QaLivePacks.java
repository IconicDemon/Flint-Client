package com.flintclient.qa;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;

/**
 * Dev-only QA helpers for packs the Flint app adds while the game runs (game-runtime Mc263LivePacks), called from tours with
 * {@code call com.flintclient.qa.QaLivePacks.METHOD}: what Minecraft and Iris have selected, and their pack screens opened.
 */
public final class QaLivePacks {
	private static final String IRIS = "net.irisshaders.iris.Iris";

	private QaLivePacks() {
	}

	/** The resource packs Minecraft has selected (bottom first) and what options.txt keeps. */
	public static String packs() {
		Minecraft mc = Minecraft.getInstance();
		return "selected=" + mc.getResourcePackRepository().getSelectedIds() + " options=" + mc.options.resourcePacks + " incompatible="
			+ mc.options.incompatibleResourcePacks;
	}

	/** Iris's selected pack, whether shaders are on, and the pack it loaded. */
	public static String iris() {
		try {
			Class<?> iris = Class.forName(IRIS);
			Object config = iris.getMethod("getIrisConfig").invoke(null);
			Object name = config.getClass().getMethod("getShaderPackName").invoke(config);
			Object on = config.getClass().getMethod("areShadersEnabled").invoke(config);
			Object current = iris.getMethod("getCurrentPackName").invoke(null);
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object inUse = api.getMethod("isShaderPackInUse").invoke(api.getMethod("getInstance").invoke(null));
			return "selected=" + name + " enabled=" + on + " loaded=" + current + " inUse=" + inUse;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "no Iris: " + e;
		}
	}

	/** Minecraft's Resource Packs screen, as Options opens it. */
	public static String openPacks() {
		Minecraft mc = Minecraft.getInstance();
		Screen parent = QaGame.screen(mc);
		QaGame.setScreen(mc, new PackSelectionScreen(mc.getResourcePackRepository(), repository -> {
			mc.options.updateResourcePacks(repository);
			QaGame.setScreen(mc, parent);
		}, mc.getResourcePackDirectory(), Component.translatable("resourcePack.title")));
		return "opened";
	}

	/** Iris's shader pack screen, through its public API. */
	public static String openShaders() {
		try {
			Minecraft mc = Minecraft.getInstance();
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object screen = api.getMethod("openMainIrisScreenObj", Object.class).invoke(api.getMethod("getInstance").invoke(null), QaGame.screen(mc));
			QaGame.setScreen(mc, (Screen) screen);
			return screen.getClass().getName();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "couldn't: " + e;
		}
	}

	/** Back to the game. */
	public static String close() {
		QaGame.setScreen(Minecraft.getInstance(), null);
		return "closed";
	}
}
