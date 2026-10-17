package com.flintclient.bench;

import com.mojang.blaze3d.platform.Window;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

/** The "env" block of the result: JVM, loaded mods, the options that matter for performance, window size. */
final class Env {
	private Env() {
	}

	/** JVM facts, available from mod initialisation on. */
	static void captureJvm(Map<String, Object> env, List<String> gcBeans) {
		RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
		env.put("java", System.getProperty("java.version"));
		env.put("vm", System.getProperty("java.vm.name"));
		env.put("jvmArgs", new ArrayList<>(runtime.getInputArguments()));
		env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
		env.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
		env.put("gcBeans", gcBeans);
		List<Map<String, Object>> mods = new ArrayList<>();
		FabricLoader.getInstance().getAllMods().stream()
			.sorted(Comparator.comparing((ModContainer mod) -> mod.getMetadata().getId()))
			.forEach(mod -> {
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("id", mod.getMetadata().getId());
				entry.put("version", mod.getMetadata().getVersion().getFriendlyString());
				mods.add(entry);
			});
		env.put("mods", mods);
	}

	/** Game options and window size; called on the client thread once the title screen is up. */
	static void captureGame(Minecraft mc, Map<String, Object> env) {
		Options options = mc.options;
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("renderDistance", option(options.renderDistance()));
		values.put("simulationDistance", option(options.simulationDistance()));
		values.put("framerateLimit", option(options.framerateLimit()));
		values.put("enableVsync", option(options.enableVsync()));
		values.put("graphicsPreset", option(options.graphicsPreset()));
		values.put("fullscreen", option(options.fullscreen()));
		values.put("guiScale", option(options.guiScale()));
		values.put("inactivityFpsLimit", option(options.inactivityFpsLimit()));
		values.put("chunkSectionFadeInTime", option(options.chunkSectionFadeInTime()));
		values.put("pauseOnLostFocus", options.pauseOnLostFocus);
		env.put("options", values);

		Window window = mc.getWindow();
		Map<String, Object> size = new LinkedHashMap<>();
		// Window.getWidth()/getHeight() are the framebuffer; getScreenWidth()/getScreenHeight() the window.
		size.put("width", window.getScreenWidth());
		size.put("height", window.getScreenHeight());
		size.put("framebufferWidth", window.getWidth());
		size.put("framebufferHeight", window.getHeight());
		size.put("guiScale", window.getGuiScale());
		size.put("exclusiveFullscreen", window.isExclusiveFullscreen());
		env.put("window", size);
	}

	private static Object option(OptionInstance<?> option) {
		Object value = option.get();
		return value instanceof Enum<?> constant ? constant.name() : value;
	}
}
