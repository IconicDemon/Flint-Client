package com.flintclient.bench;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.server.IntegratedServer;

/**
 * Reads game and JVM state for the once-per-second samples and the terrain-ready test (client thread).
 *
 * <p>Terrain numbers come from vanilla's renderer API. Sodium overwrites
 * {@code LevelExtractor.countRenderedSections} and {@code LevelRenderer.hasRenderedAllSections} with its
 * own (visible chunk count, "terrain render complete"), so those keep working with its numbers; vanilla's
 * section compile queue is unused under Sodium and is recorded as unknown there.
 */
final class Probe {
	private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
	static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
	static final String[] THROTTLE_NAMES = throttleNames();

	// Cleared when the terrain API is missing (another renderer replaced it), so the scenario stops waiting.
	static boolean terrainAvailable = true;
	private static boolean terrainErrorLogged;

	private Probe() {
	}

	static void sample(Minecraft mc, SampleLog log, long nowNs) {
		int i = log.next(nowNs);
		if (i < 0) {
			return;
		}
		MemoryUsage heap = MEMORY.getHeapMemoryUsage();
		log.heapUsed[i] = heap.getUsed();
		log.heapCommitted[i] = heap.getCommitted();
		log.nonHeapUsed[i] = MEMORY.getNonHeapMemoryUsage().getUsed();
		log.throttle[i] = (byte) mc.getFramerateLimitTracker().getThrottleReason().ordinal();
		log.focused[i] = (byte) (mc.getWindow().isFocused() ? 1 : 0);

		ClientLevel level = mc.level;
		if (level != null) {
			log.loadedChunks[i] = level.getChunkSource().getLoadedChunksCount();
			log.renderedSections[i] = renderedSections(mc);
			log.compileQueue[i] = compileQueue(mc);
		}
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			log.serverTickNs[i] = server.getAverageTickTimeNanos();
		}
		LocalPlayer player = mc.player;
		if (player != null) {
			log.playerX[i] = player.getX();
		}
	}

	/** Vanilla's own "terrain fully rendered" test (GameRenderer.takeAutoScreenshot). */
	static boolean terrainReady(Minecraft mc) {
		try {
			return mc.levelExtractor.countRenderedSections() > 10 && mc.levelRenderer.hasRenderedAllSections();
		} catch (LinkageError e) {
			if (terrainAvailable) {
				FlintBench.LOGGER.warn("[flintbench] terrain API unavailable; load times will omit terrain", e);
			}
			terrainAvailable = false;
			return false;
		} catch (RuntimeException e) {
			// Renderer not set up yet (for example Sodium's, right after a world change): not ready this tick.
			// The terrain timeout ends the wait if this keeps happening.
			if (!terrainErrorLogged) {
				terrainErrorLogged = true;
				FlintBench.LOGGER.warn("[flintbench] terrain check failed; retrying every tick", e);
			}
			return false;
		}
	}

	private static int renderedSections(Minecraft mc) {
		try {
			return mc.levelExtractor.countRenderedSections();
		} catch (Throwable t) {
			return SampleLog.UNKNOWN;
		}
	}

	private static int compileQueue(Minecraft mc) {
		if (SODIUM) {
			return SampleLog.UNKNOWN;
		}
		try {
			SectionRenderDispatcher dispatcher = mc.levelRenderer.sectionRenderDispatcher();
			return dispatcher == null ? SampleLog.UNKNOWN : dispatcher.getCompileQueueSize();
		} catch (Throwable t) {
			return SampleLog.UNKNOWN;
		}
	}

	/** Copies the integrated server's last-100-tick ring (unordered), or null outside a world. */
	static long[] serverTickWindow(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		return server == null ? null : server.getTickTimesNanos().clone();
	}

	private static String[] throttleNames() {
		FramerateLimitTracker.FramerateThrottleReason[] reasons = FramerateLimitTracker.FramerateThrottleReason.values();
		String[] names = new String[reasons.length];
		for (int i = 0; i < reasons.length; i++) {
			names[i] = reasons[i].name();
		}
		return names;
	}
}
