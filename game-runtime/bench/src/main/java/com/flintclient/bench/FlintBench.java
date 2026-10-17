package com.flintclient.bench;

import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the flintbench probe, a dev-only benchmark tool that is never shipped in the Flint app.
 * It does nothing unless the JVM is started with {@code -Dflintbench.out=<absolute path of the result
 * JSON>}: every hook first checks {@link #ENABLED}, and the recording buffers are not even allocated.
 *
 * <p>Other properties: {@code flintbench.seed} (world seed, default 20261015), {@code flintbench.label}
 * (free text copied into the result) and {@code flintbench.quick=true} (every segment a fifth as long,
 * for harness smoke tests).
 */
public final class FlintBench implements ClientModInitializer {
	public static final boolean ENABLED = System.getProperty("flintbench.out") != null;

	static final Logger LOGGER = LoggerFactory.getLogger("flintbench");
	static final String OUT = System.getProperty("flintbench.out");
	static final long SEED = Long.getLong("flintbench.seed", 20261015L);
	static final String LABEL = System.getProperty("flintbench.label", "");
	static final boolean QUICK = Boolean.getBoolean("flintbench.quick");

	// About 16 MB of primitive arrays, only when enabled. Capacity: 600k frames, 100k client ticks.
	static final FrameRecorder FRAMES = ENABLED ? new FrameRecorder(600_000, 100_000) : null;
	static final SampleLog SAMPLES = ENABLED ? new SampleLog(4_096) : null;
	static final GcRing GC = ENABLED ? new GcRing(20_000) : null;

	@Override
	public void onInitializeClient() {
		if (!ENABLED) {
			return;
		}
		LOGGER.info("[flintbench] enabled: out={} seed={} label={} quick={}", OUT, SEED, LABEL, QUICK);
		List<String> gcBeans;
		try {
			gcBeans = GcListener.install(GC);
		} catch (Throwable t) {
			LOGGER.warn("[flintbench] GC notifications unavailable", t);
			gcBeans = List.of();
		}
		BenchDirector.init(gcBeans);
	}

	// Hooks, called by the mixins only when ENABLED.

	/** Once per rendered frame, with the full frame interval (DebugScreenOverlay.logFrameDuration). */
	public static void onFrame(long frameIntervalNs) {
		FRAMES.onFrame(frameIntervalNs, Minecraft.getInstance().getFrameTimeNs(), System.nanoTime());
	}

	public static void onTickStart() {
		FRAMES.tickStarted(System.nanoTime());
	}

	public static void onTickEnd(Minecraft minecraft) {
		FRAMES.tickEnded(System.nanoTime());
		BenchDirector.clientTick(minecraft);
	}
}
