package com.flintclient.client.ui;

import com.flintclient.client.render.MaskAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Owns the once-per-frame work of Flint's drawing: ticking {@link Motion}'s frame time and starting the
 * {@link MaskAtlas} frame (which frees textures the previous frame replaced). Everything Flint draws in a
 * frame (the HUD, then a Flint screen) calls {@link #begin}; only the first call of a frame does the work,
 * so a HUD and a screen in the same frame can't tick motion twice (which would zero the frame time) or
 * free a texture the HUD's draws, collected earlier in the frame, still use.
 *
 * <p>A frame is identified two ways, and a call starts a new frame if either changed: the frame counter
 * {@link #advance()}d by {@code PerformanceCore.onFrame} (called from the {@code logFrameDuration} sample
 * Minecraft takes once per rendered frame, after the GUI was extracted and drawn), and the
 * {@link GuiGraphicsExtractor} itself, which {@code Gui.extractRenderState} creates anew for each frame
 * and hands to the HUD and the screen alike. The second keeps frames apart even when no sample is taken
 * (Minecraft's panorama capture extracts frames without one).
 *
 * <p>HUD and screen together: the HUD calls {@code begin}, draws, then {@link #end()} so its new shapes
 * are uploaded before Minecraft draws the frame. A Flint screen drawn after it in the same frame calls
 * {@code begin} again: that only resumes batching ({@link MaskAtlas#resumeFrame()}), and the screen's own
 * {@code endFrame} at its end uploads what it added. Ending twice is harmless ({@code endFrame} does
 * nothing outside a frame). Render thread only.
 */
public final class FlintFrame {
	/** What starting, resuming and ending a frame's drawing do; apart from the decision so tests can watch it. */
	interface Work {
		/** The first begin of a frame: tick motion, start the atlas frame (frees textures replaced last frame). */
		void start();

		/** A later begin in the same frame: batch new shapes again, free nothing. */
		void resume();

		/** A part of the frame finished drawing: upload its new shapes. */
		void end();
	}

	private static final Work MINECRAFT = new Work() {
		@Override
		public void start() {
			Motion.tickFrame();
			MaskAtlas.get().beginFrame(Math.max(1, Minecraft.getInstance().getWindow().getGuiScale()));
		}

		@Override
		public void resume() {
			MaskAtlas.get().resumeFrame();
		}

		@Override
		public void end() {
			MaskAtlas.get().endFrame();
		}
	};
	private static final Gate GATE = new Gate(MINECRAFT);
	private static long frame;

	private FlintFrame() {
	}

	/** From {@code PerformanceCore.onFrame}, once per rendered frame: allocation-free. */
	public static void advance() {
		frame++;
	}

	/** Starts Flint's drawing for this frame; only the first call in a frame ticks motion and starts the atlas frame. */
	public static void begin(GuiGraphicsExtractor graphics) {
		GATE.begin(frame, graphics);
	}

	/** Uploads the shapes added since {@link #begin}; call when a part of the frame (HUD or screen) has finished drawing. */
	public static void end() {
		GATE.end();
	}

	static boolean isNewFrame(long frame, Object graphics, long begunFrame, @Nullable Object begunGraphics) {
		return frame != begunFrame || graphics != begunGraphics;
	}

	/** The once-per-frame decision: which begin of a frame starts it and which only resume it. */
	static final class Gate {
		private final Work work;
		private long begunFrame = -1;
		/** The extractor of the frame last begun (compared by identity, never used to draw). */
		private @Nullable Object begunGraphics;

		Gate(Work work) {
			this.work = work;
		}

		void begin(long frame, Object graphics) {
			if (!isNewFrame(frame, graphics, begunFrame, begunGraphics)) {
				work.resume();
				return;
			}
			begunFrame = frame;
			begunGraphics = graphics;
			work.start();
		}

		void end() {
			work.end();
		}
	}
}
