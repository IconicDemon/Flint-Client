package com.flintclient.qa.legacy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.GameOptions;
import org.lwjgl.opengl.GL11;

/**
 * The modern QA mod's QaStamp on 1.8.9, for screen-capture tests (qa/tools/capture-test.mjs): while on, each frame carries its
 * number as a row of black and white blocks in the bottom-left corner of the window (scissored clears of the default
 * framebuffer just before Display.update, the GL state put back), and the times around Display.update are kept. Plus the video
 * options a capture tour switches (1.8.9's QA has no option step). Dev only.
 */
public final class LegacyStamp {
	/** White, black, 20 bits of the frame number, even parity; BLOCK pixels each (as QaStamp). */
	static final int BLOCKS = 23;
	static final int DATA_BITS = 20;
	static final int BLOCK = 20;
	private static final int MAX = 400000;
	private static final int[] NUMBER = new int[MAX];
	private static final long[] BEFORE = new long[MAX];
	private static final long[] AFTER = new long[MAX];
	private static final FloatBuffer CLEAR = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
	private static final IntBuffer BOX = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();
	private static boolean on;
	private static int frame = 1;
	private static int count;
	private static long before;

	private LegacyStamp() {
	}

	public static String on() {
		on = true;
		count = 0;
		return "stamping from frame " + frame;
	}

	static boolean white(int i) {
		int n = frame & ((1 << DATA_BITS) - 1);
		if (i == 0) {
			return true;
		}
		if (i == 1) {
			return false;
		}
		if (i < 2 + DATA_BITS) {
			return ((n >> (DATA_BITS - 1 - (i - 2))) & 1) != 0;
		}
		return (Integer.bitCount(n) & 1) != 0;
	}

	/** From the head of Minecraft.updateDisplay: the picture is finished in the default framebuffer. */
	public static void beforePresent() {
		if (!on) {
			return;
		}
		boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		CLEAR.clear();
		GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, CLEAR);
		BOX.clear();
		GL11.glGetInteger(GL11.GL_SCISSOR_BOX, BOX);
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		for (int i = 0; i < BLOCKS; i++) {
			float v = white(i) ? 1 : 0;
			GL11.glScissor(i * BLOCK, 0, BLOCK, BLOCK);
			GL11.glClearColor(v, v, v, 1);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		}
		GL11.glScissor(BOX.get(0), BOX.get(1), BOX.get(2), BOX.get(3));
		if (!scissor) {
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
		}
		GL11.glClearColor(CLEAR.get(0), CLEAR.get(1), CLEAR.get(2), CLEAR.get(3));
		before = System.nanoTime();
	}

	/** From the end of Minecraft.updateDisplay. */
	public static void afterPresent() {
		if (!on) {
			return;
		}
		if (count < MAX) {
			NUMBER[count] = frame;
			BEFORE[count] = before;
			AFTER[count] = System.nanoTime();
			count++;
		}
		frame++;
	}

	public static String dump(String name) throws Exception {
		on = false;
		File file = new File(System.getProperty("flintqa.out", "."), "stamps-" + name + ".csv");
		Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
		try {
			w.write("frame,before_ns,after_ns\n");
			for (int i = 0; i < count; i++) {
				w.write(NUMBER[i] + "," + BEFORE[i] + "," + AFTER[i] + "\n");
			}
		} finally {
			w.close();
		}
		return "stamps " + name + " frames=" + count;
	}

	/** Max Framerate as Video Settings sets it (260: unlimited). */
	public static String maxFps(int fps) {
		Minecraft.getInstance().options.set(GameOptions.Option.FRAMERATE_LIMIT, (float) fps);
		return "maxFps " + Minecraft.getInstance().options.fpsLimit;
	}

	/** VSync as Video Settings switches it. */
	public static String vsync(boolean want) {
		GameOptions o = Minecraft.getInstance().options;
		if (o.vsync != want) {
			o.set(GameOptions.Option.ENABLE_VSYNC, 1);
		}
		return "vsync " + o.vsync;
	}

	/** Fullscreen as F11 switches it. */
	public static String fullscreen(boolean want) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isFullscreen() != want) {
			mc.toggleFullscreen();
		}
		return "fullscreen " + mc.isFullscreen();
	}
}
