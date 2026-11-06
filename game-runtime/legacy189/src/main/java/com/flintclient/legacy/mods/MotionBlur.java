/*
 * The blur is adapted from Natural Motion Blur 1.4.5 by Pasi, with IMS212, Romain H and What42Pizza (LGPL-3.0;
 * META-INF/licenses/LGPL-3.0-NaturalMotionBlur.txt).
 */
package com.flintclient.legacy.mods;

import com.flintclient.client.control.fx.NmbTiming;
import com.flintclient.client.control.model.mods.VisualMods;
import com.flintclient.legacy.module.Module;
import com.flintclient.legacy.module.Setting;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.pipeline.RenderTarget;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.entity.Entity;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.util.vector.Matrix4f;

/**
 * Motion Blur on Minecraft 1.8.9, exactly as on the other lines (the rules: {@link NmbTiming}; the look: Natural Motion Blur's
 * velocity based blur, frame blending, hybrid and accumulation, with the same settings and defaults).
 *
 * <p><b>Where</b> (the hooks: {@code mixin/GameRendererMixin}, in the world pass): at its {@code "entities"} profiler step, after
 * the solid, cutout-mipped and cutout terrain and before any entity: {@link #early}, which also reads this frame's camera
 * (OpenGL's model-view and projection matrices, view bobbing and the hurt tilt in them, and the camera entity's position); at
 * its {@code "hand"} step, before the depth buffer is cleared for the hand: {@link #late}. The hand and the HUD stay sharp.
 *
 * <p><b>How:</b> 1.8.9 keeps its depth in a render buffer, so each velocity pass copies the depth into a depth texture of its own
 * and the image into a colour texture ({@code glCopyTexSubImage2D}, no framebuffer of Flint's own needed), then draws one
 * screen-covering quad over Minecraft's framebuffer with the same shader the newer lines run, in GLSL 1.20 for 1.8.9's OpenGL
 * 2.1 ({@code assets/flintclient/shaders/gl120}). Frame blending copies the image into a ring of kept frames and draws their
 * blend; the trail draws the blend and keeps a copy of it. Texture units 0–7 are bound through {@code GlStateManager} (its cache
 * stays true), 8–11 (frame blending only) directly, each with its filter set again; the program, depth test, blending, culling,
 * alpha test and Minecraft's own textures on units 0 and 1 are put back (1.8.9's world pass sets filters on whatever texture
 * is bound, assuming its own).
 * Nothing is allocated per frame and nothing is read back. Needs OpenGL 2.0 shaders and depth textures (every graphics card
 * 1.8.9 runs on today); without them, or on any error, the mod switches itself off with one log line. Render thread only.
 */
public final class MotionBlur extends Module {
	/** Pixels nearer the eye than this (blocks) are left sharp. */
	private static final float HAND_DISTANCE = 0.114F;
	/** The texture units GlStateManager keeps track of. */
	private static final int TRACKED_UNITS = 8;

	public final Setting.Choice algorithm = option(VisualMods.MotionBlur.ALGORITHM);
	public final Setting.Decimal strength = decimal(VisualMods.MotionBlur.STRENGTH, 100, "");
	public final Setting.Bool refreshScaling = bool(VisualMods.MotionBlur.REFRESH_SCALING);
	public final Setting.Choice profile = option(VisualMods.MotionBlur.PROFILE);
	public final Setting.Choice quality = option(VisualMods.MotionBlur.QUALITY);
	public final Setting.Bool movement = bool(VisualMods.MotionBlur.MOVEMENT);

	// GPU resources.
	private boolean programsTried;
	private int velocityProgram;
	private int blendProgram;
	private int accumProgram;
	private int source = -1;
	private int depth = -1;
	private int trail = -1;
	private final int[] history = new int[NmbTiming.MAX_HISTORY];
	private int texW;
	private int texH;
	private int historyW;
	private int historyH;

	// Uniform locations.
	private int vIn, vDepth, vMvInverse, vProjInverse, vPrevView, vPrevProjection, vDelta, vBlend, vRes, vDensity, vMax, vProfile, vMode, vFar,
		vReversed, vDepthToNdc, vHand;
	private final int[] bSamplers = new int[NmbTiming.MAX_HISTORY];
	private int bWeights0, bWeights1, bWeights2, bInvTotal, bCount;
	private int aIn, aPrev, aBlend, aMax, aDither;

	// Across frames.
	private boolean accumValid;
	private int ditherFrame;
	private int lastMode = -1;
	private final double[] times = new double[NmbTiming.MAX_HISTORY];
	private final int[] slots = new int[NmbTiming.MAX_HISTORY];
	private final float[] weights = new float[NmbTiming.MAX_HISTORY];
	private final int[] order = new int[NmbTiming.MAX_HISTORY];
	private int historyWrite;
	private int historyFilled;
	private long lastFrameNanos;
	private float smoothedFps;
	private int refreshRate = 60;
	private long refreshCheckedNanos;
	private int levelIdentity;
	private int lastWidth;
	private int lastHeight;
	private boolean lastDetached;
	private boolean previousReady;
	private final Matrix4f prevView = new Matrix4f();
	private final Matrix4f prevProjection = new Matrix4f();
	private double prevX;
	private double prevY;
	private double prevZ;

	// This frame.
	private boolean begun;
	private boolean frameActive;
	private int frameMode;
	private float frameStrength;
	private float frameSeconds;
	private float fps;
	private boolean frameDetached;
	private boolean frameMovement;
	private boolean frameTranslating;
	private final float[] invViewArray = new float[16];
	private final float[] invProjectionArray = new float[16];
	private final float[] prevViewArray = new float[16];
	private final float[] prevProjectionArray = new float[16];
	private int frameProfile;
	private int frameQuality;
	private boolean velocityReady;
	private float velocityBlend;
	private int earlyMode = NmbTiming.NONE;
	private boolean earlyDrawn;
	private final Matrix4f view = new Matrix4f();
	private final Matrix4f projection = new Matrix4f();
	private final Matrix4f invView = new Matrix4f();
	private final Matrix4f invProjection = new Matrix4f();
	private final Matrix4f framePrevView = new Matrix4f();
	private final Matrix4f framePrevProjection = new Matrix4f();
	private float deltaX;
	private float deltaY;
	private float deltaZ;
	private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

	public MotionBlur() {
		super("motion_blur", "motionblur");
	}

	@Override
	protected void onDisable() {
		release();
	}

	@Override
	protected void onEnable() {
		reset();
	}

	/** A new world: the next frame isn't blurred from the last one, and kept frames and the trail start over. */
	public void reset() {
		previousReady = false;
		accumValid = false;
		historyFilled = 0;
	}

	// ---------- the frame ----------

	/** At the world pass's {@code "entities"} step (pass 2 is a normal frame; anaglyph's two are left alone). */
	public void early(int pass, float tickDelta) {
		if (pass != 2) {
			return;
		}
		beginFrame(tickDelta);
		if (!frameActive || !velocityReady || earlyDrawn || earlyMode == NmbTiming.NONE) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		begin();
		try {
			velocity(mc, earlyMode);
			earlyDrawn = true;
		} finally {
			end(mc);
		}
	}

	/** At the world pass's {@code "hand"} step, before the depth is cleared for the hand: the camera's blur, then frame blending or the trail. */
	public void late(int pass, float tickDelta) {
		if (pass != 2) {
			return;
		}
		if (!begun) {
			beginFrame(tickDelta);
		}
		begun = false;
		if (!frameActive) {
			return;
		}
		frameActive = false;
		if (!velocityReady && !NmbTiming.usesFrameBlending(frameMode) && !NmbTiming.usesAccumulation(frameMode)) {
			// A still camera with velocity blur: nothing to draw.
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		begin();
		try {
			if (velocityReady) {
				int mode = NmbTiming.lateMode(frameMovement, frameDetached, earlyDrawn, frameTranslating);
				if (mode != NmbTiming.NONE) {
					velocity(mc, mode);
				}
			}
			if (NmbTiming.usesFrameBlending(frameMode)) {
				frameBlend(mc, frameStrength);
			} else if (NmbTiming.usesAccumulation(frameMode)) {
				accumulate(mc, NmbTiming.kept(frameMode, frameStrength, frameSeconds, refreshRate), frameMode == NmbTiming.ACCUMULATION_MAX);
			}
		} finally {
			end(mc);
		}
	}

	private void beginFrame(float tickDelta) {
		begun = true;
		long now = System.nanoTime();
		float seconds = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1.0E9F;
		lastFrameNanos = now;
		frameSeconds = seconds;
		fps = seconds > 0 && seconds < 1 ? 1 / seconds : 0;
		smoothedFps = NmbTiming.smoothFps(smoothedFps, fps);
		frameActive = false;
		velocityReady = false;
		earlyMode = NmbTiming.NONE;
		earlyDrawn = false;
		Minecraft mc = Minecraft.getInstance();
		Entity camera = mc.getCamera();
		float strength = this.strength.decimal();
		if (!enabled() || strength <= 0 || camera == null || mc.world == null || mc.width <= 0 || mc.height <= 0
			|| !programs()) {
			previousReady = false;
			return;
		}
		matrixBuffer.clear();
		GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrixBuffer);
		view.load(matrixBuffer);
		matrixBuffer.clear();
		GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, matrixBuffer);
		projection.load(matrixBuffer);
		double x = camera.prevX + (camera.x - camera.prevX) * tickDelta;
		double y = camera.prevY + (camera.y - camera.prevY) * tickDelta;
		double z = camera.prevZ + (camera.z - camera.prevZ) * tickDelta;
		int mode = algorithm.index();
		boolean detached = mc.options.perspective != 0 || mc.player != null && mc.player.vehicle != null;
		int width = mc.width;
		int height = mc.height;
		int level = System.identityHashCode(mc.world);
		double dx = x - prevX;
		double dy = y - prevY;
		double dz = z - prevZ;
		boolean continuous = previousReady && level == levelIdentity && width == lastWidth && height == lastHeight && detached == lastDetached
			&& mode == lastMode && NmbTiming.continues(frameSeconds, dx, dy, dz);
		levelIdentity = level;
		lastWidth = width;
		lastHeight = height;
		lastDetached = detached;
		if (mode != lastMode || !continuous) {
			lastMode = mode;
			accumValid = false;
			historyFilled = 0;
		}
		updateRefreshRate();
		frameActive = true;
		frameMode = mode;
		frameStrength = strength;
		frameDetached = detached;
		frameMovement = movement.on();
		frameProfile = profile.index();
		frameQuality = quality.index();
		boolean still = dx == 0 && dy == 0 && dz == 0 && same(view, prevView) && same(projection, prevProjection);
		if (NmbTiming.usesVelocity(mode) && continuous && !still && Matrix4f.invert(view, invView) != null
			&& Matrix4f.invert(projection, invProjection) != null) {
			framePrevView.load(prevView);
			framePrevProjection.load(prevProjection);
			deltaX = (float) dx;
			deltaY = (float) dy;
			deltaZ = (float) dz;
			velocityBlend = mode == NmbTiming.HYBRID ? NmbTiming.hybridFiller(strength, fps, smoothedFps, refreshRate, historyFilled)
				: NmbTiming.velocityStrength(strength, fps, refreshRate, refreshScaling.on());
			boolean translating = dx != 0 || dy != 0 || dz != 0;
			// Only a turn or a zoom that moves the image under half a pixel (a field of view settling): nothing to draw.
			boolean drift = !translating && NmbTiming.turnPixels(columns(invProjection, invProjectionArray), columns(invView, invViewArray),
				columns(framePrevView, prevViewArray), columns(framePrevProjection, prevProjectionArray), 0, width, height) * velocityBlend < 0.5F;
			frameTranslating = translating;
			velocityReady = velocityBlend > 0 && !drift;
			earlyMode = velocityReady ? NmbTiming.earlyMode(frameMovement, detached, translating) : NmbTiming.NONE;
		}
		prevView.load(view);
		prevProjection.load(projection);
		prevX = x;
		prevY = y;
		prevZ = z;
		previousReady = true;
	}

	/** {@code m} into {@code out}, column by column (as it is stored). */
	private static float[] columns(Matrix4f m, float[] out) {
		out[0] = m.m00;
		out[1] = m.m01;
		out[2] = m.m02;
		out[3] = m.m03;
		out[4] = m.m10;
		out[5] = m.m11;
		out[6] = m.m12;
		out[7] = m.m13;
		out[8] = m.m20;
		out[9] = m.m21;
		out[10] = m.m22;
		out[11] = m.m23;
		out[12] = m.m30;
		out[13] = m.m31;
		out[14] = m.m32;
		out[15] = m.m33;
		return out;
	}

	private static boolean same(Matrix4f a, Matrix4f b) {
		return a.m00 == b.m00 && a.m01 == b.m01 && a.m02 == b.m02 && a.m03 == b.m03 && a.m10 == b.m10 && a.m11 == b.m11 && a.m12 == b.m12
			&& a.m13 == b.m13 && a.m20 == b.m20 && a.m21 == b.m21 && a.m22 == b.m22 && a.m23 == b.m23 && a.m30 == b.m30 && a.m31 == b.m31
			&& a.m32 == b.m32 && a.m33 == b.m33;
	}

	private void updateRefreshRate() {
		long now = System.nanoTime();
		if (now - refreshCheckedNanos < 1_000_000_000L) {
			return;
		}
		refreshCheckedNanos = now;
		int hz = Display.getDesktopDisplayMode().getFrequency();
		refreshRate = hz > 0 ? hz : 60;
	}

	// ---------- velocity ----------

	private void velocity(Minecraft mc, int mode) {
		int w = mc.width;
		int h = mc.height;
		ensureTextures(w, h);
		// This frame's depth and image, from Minecraft's framebuffer (bound for reading as the world is drawn); unit 0 is left active.
		bindUnit(1, depth, GL11.GL_NEAREST);
		GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
		bindUnit(0, source, GL11.GL_LINEAR);
		GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
		GLX.useProgram(velocityProgram);
		uniformMatrix(vMvInverse, invView);
		uniformMatrix(vProjInverse, invProjection);
		uniformMatrix(vPrevView, framePrevView);
		uniformMatrix(vPrevProjection, framePrevProjection);
		GL20.glUniform3f(vDelta, deltaX, deltaY, deltaZ);
		GL20.glUniform1f(vBlend, velocityBlend);
		GL20.glUniform2f(vRes, w, h);
		GL20.glUniform1f(vDensity, NmbTiming.sampleDensity(frameQuality));
		GL20.glUniform1i(vMax, NmbTiming.maxSamples(frameQuality));
		GL20.glUniform1i(vProfile, frameProfile);
		GL20.glUniform1i(vMode, mode);
		// 1.8.9 draws with ordinary depth (far is 1), from -1 to 1 in clip space.
		GL20.glUniform1f(vFar, 1);
		GL20.glUniform1f(vReversed, 0);
		GL20.glUniform2f(vDepthToNdc, 2, -1);
		GL20.glUniform1f(vHand, HAND_DISTANCE);
		GL20.glUniform1i(vIn, 0);
		GL20.glUniform1i(vDepth, 1);
		quad();
	}

	private void uniformMatrix(int location, Matrix4f m) {
		matrixBuffer.clear();
		m.store(matrixBuffer);
		matrixBuffer.flip();
		GL20.glUniformMatrix4(location, false, matrixBuffer);
	}

	// ---------- frame blending ----------

	private void frameBlend(Minecraft mc, float strength) {
		int w = mc.width;
		int h = mc.height;
		if (history[0] <= 0 || historyW != w || historyH != h) {
			releaseHistory();
			for (int i = 0; i < history.length; i++) {
				history[i] = newTexture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, w, h, GL11.GL_NEAREST);
			}
			historyW = w;
			historyH = h;
			historyFilled = 0;
			historyWrite = 0;
		}
		bindUnit(0, history[historyWrite], GL11.GL_NEAREST);
		GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
		double now = System.nanoTime() * 1.0E-9;
		times[historyWrite] = now;
		historyWrite = (historyWrite + 1) % history.length;
		historyFilled = Math.min(historyFilled + 1, history.length);
		int first = historyWrite - historyFilled;
		for (int i = 0; i < historyFilled; i++) {
			order[i] = Math.floorMod(first + i, history.length);
		}
		int count = NmbTiming.weights(now, times, order, historyFilled, smoothedFps, refreshRate, strength, slots, weights);
		if (count <= 1) {
			return;
		}
		float total = 0;
		for (int i = 0; i < count; i++) {
			total += weights[i];
		}
		GLX.useProgram(blendProgram);
		GL20.glUniform4f(bWeights0, weights[0], weights[1], weights[2], weights[3]);
		GL20.glUniform4f(bWeights1, weights[4], weights[5], weights[6], weights[7]);
		GL20.glUniform4f(bWeights2, weights[8], weights[9], weights[10], weights[11]);
		GL20.glUniform1f(bInvTotal, total > 0 ? 1 / total : 1);
		GL20.glUniform1i(bCount, count);
		// Units 8-11 first (outside GlStateManager's cache), then 0-7 through it, ending on a unit it knows is active.
		for (int i = NmbTiming.MAX_HISTORY - 1; i >= 0; i--) {
			GL20.glUniform1i(bSamplers[i], i);
			bindUnit(i, history[slots[Math.min(i, count - 1)]], GL11.GL_NEAREST);
		}
		quad();
	}

	// ---------- accumulation ----------

	private void accumulate(Minecraft mc, float kept, boolean max) {
		int w = mc.width;
		int h = mc.height;
		ensureTextures(w, h);
		if (!accumValid || !(kept > 0)) {
			// A fresh trail starts as this frame.
			bindUnit(1, trail, GL11.GL_NEAREST);
			GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
			accumValid = true;
			return;
		}
		bindUnit(1, trail, GL11.GL_NEAREST);
		bindUnit(0, source, GL11.GL_LINEAR);
		GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
		ditherFrame = (ditherFrame + 1) & 1023;
		GLX.useProgram(accumProgram);
		GL20.glUniform1f(aBlend, kept);
		GL20.glUniform1i(aMax, max ? 1 : 0);
		GL20.glUniform1f(aDither, ditherFrame * 5.588238F);
		GL20.glUniform1i(aIn, 0);
		GL20.glUniform1i(aPrev, 1);
		quad();
		// The blend becomes the trail.
		bindUnit(1, trail, GL11.GL_NEAREST);
		GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
	}

	// ---------- OpenGL ----------

	/** The textures Minecraft had bound on units 0 and 1 before a hook's passes (put back after them). */
	private static int keptTexture0;
	private static int keptTexture1;
	private static final IntBuffer BINDING = BufferUtils.createIntBuffer(16);

	/**
	 * Before a hook's passes: nothing tested, blended, culled or alpha-tested; a known active texture unit; Minecraft's textures on
	 * units 0 and 1 noted, so they can be put back (1.8.9 sets filters on whatever texture is bound: its world pass assumes the
	 * block atlas and the light map are still there).
	 */
	private static void begin() {
		GlStateManager.disableDepthTest();
		GlStateManager.depthMask(false);
		GlStateManager.disableBlend();
		GlStateManager.disableCull();
		GlStateManager.disableAlphaTest();
		GlStateManager.colorMask(true, true, true, true);
		// Make the cache's idea of the active unit true whatever came before (the second call always reaches OpenGL).
		GlStateManager.activeTexture(GLX.GL_TEXTURE0 + 1);
		GlStateManager.activeTexture(GLX.GL_TEXTURE0);
		keptTexture0 = boundTexture();
		GlStateManager.activeTexture(GLX.GL_TEXTURE0 + 1);
		keptTexture1 = boundTexture();
		GlStateManager.activeTexture(GLX.GL_TEXTURE0);
	}

	private static int boundTexture() {
		BINDING.clear();
		GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D, BINDING);
		return BINDING.get(0);
	}

	/** After them: no program, Minecraft's usual state and textures, texture unit 0 active, the world's viewport. */
	private static void end(Minecraft mc) {
		GLX.useProgram(0);
		GlStateManager.activeTexture(GLX.GL_TEXTURE0 + 1);
		GlStateManager.bindTexture(keptTexture1);
		GlStateManager.activeTexture(GLX.GL_TEXTURE0);
		GlStateManager.bindTexture(keptTexture0);
		GlStateManager.enableAlphaTest();
		GlStateManager.enableCull();
		GlStateManager.depthMask(true);
		GlStateManager.enableDepthTest();
		GlStateManager.viewport(0, 0, mc.width, mc.height);
	}

	/**
	 * Binds one of Flint's textures to {@code unit} with its filter set again (something else may have changed it while it was
	 * bound: a mipmapped filter on a texture without mipmaps reads as black): through GlStateManager for the units it tracks,
	 * directly for the rest.
	 */
	private static void bindUnit(int unit, int texture, int filter) {
		if (unit < TRACKED_UNITS) {
			GlStateManager.activeTexture(GLX.GL_TEXTURE0 + unit);
			GlStateManager.bindTexture(texture);
			setFilter(filter);
		} else {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
			setFilter(filter);
			// Back to a unit GlStateManager knows is active (it last set unit 7 or lower; 0 is set again before anything else).
			GL13.glActiveTexture(GL13.GL_TEXTURE0);
			GlStateManager.activeTexture(GLX.GL_TEXTURE0 + 1);
			GlStateManager.activeTexture(GLX.GL_TEXTURE0);
		}
	}

	private static void setFilter(int filter) {
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
	}

	/**
	 * One quad over the whole framebuffer, in clip space: the vertex shader ignores the matrices and takes the texture
	 * coordinates from the position (immediate-mode texture coordinates read back wrong on some drivers at some points of the
	 * frame, depending on texture-unit state).
	 */
	private static void quad() {
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex2f(-1, -1);
		GL11.glVertex2f(1, -1);
		GL11.glVertex2f(1, 1);
		GL11.glVertex2f(-1, 1);
		GL11.glEnd();
	}

	private void ensureTextures(int w, int h) {
		if (source > 0 && texW == w && texH == h) {
			return;
		}
		releaseTextures();
		source = newTexture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, w, h, GL11.GL_LINEAR);
		trail = newTexture(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, w, h, GL11.GL_NEAREST);
		depth = newTexture(GL14.GL_DEPTH_COMPONENT24, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, w, h, GL11.GL_NEAREST);
		texW = w;
		texH = h;
		accumValid = false;
	}

	private static int newTexture(int internalFormat, int format, int type, int w, int h, int filter) {
		int id = GlStateManager.genTextures();
		GlStateManager.activeTexture(GLX.GL_TEXTURE0);
		GlStateManager.bindTexture(id);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		if (format == GL11.GL_DEPTH_COMPONENT) {
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_DEPTH_TEXTURE_MODE, GL11.GL_LUMINANCE);
		}
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, (IntBuffer) null);
		return id;
	}

	/** Compiles the three programs once; a driver without OpenGL 2.0 shaders or depth textures can't run the blur. */
	private boolean programs() {
		if (programsTried) {
			return velocityProgram > 0;
		}
		programsTried = true;
		ContextCapabilities caps = GLContext.getCapabilities();
		if (!caps.OpenGL20 || !caps.OpenGL14) {
			throw new IllegalStateException("motion blur needs OpenGL 2.0 (shaders and depth textures)");
		}
		int vertex = shader(GL20.GL_VERTEX_SHADER, "nmb_screen.vsh");
		try {
			velocityProgram = link(vertex, "nmb_velocity.fsh");
			blendProgram = link(vertex, "nmb_frame_blend.fsh");
			accumProgram = link(vertex, "nmb_accumulate.fsh");
		} finally {
			GL20.glDeleteShader(vertex);
		}
		vIn = GL20.glGetUniformLocation(velocityProgram, "InSampler");
		vDepth = GL20.glGetUniformLocation(velocityProgram, "DepthSampler");
		vMvInverse = GL20.glGetUniformLocation(velocityProgram, "MvInverse");
		vProjInverse = GL20.glGetUniformLocation(velocityProgram, "ProjInverse");
		vPrevView = GL20.glGetUniformLocation(velocityProgram, "PrevModelView");
		vPrevProjection = GL20.glGetUniformLocation(velocityProgram, "PrevProjection");
		vDelta = GL20.glGetUniformLocation(velocityProgram, "CameraDelta");
		vBlend = GL20.glGetUniformLocation(velocityProgram, "BlendFactor");
		vRes = GL20.glGetUniformLocation(velocityProgram, "ViewRes");
		vDensity = GL20.glGetUniformLocation(velocityProgram, "SampleDensity");
		vMax = GL20.glGetUniformLocation(velocityProgram, "MaxSamples");
		vProfile = GL20.glGetUniformLocation(velocityProgram, "BlurProfile");
		vMode = GL20.glGetUniformLocation(velocityProgram, "Mode");
		vFar = GL20.glGetUniformLocation(velocityProgram, "FarDepth");
		vReversed = GL20.glGetUniformLocation(velocityProgram, "Reversed");
		vDepthToNdc = GL20.glGetUniformLocation(velocityProgram, "DepthToNdc");
		vHand = GL20.glGetUniformLocation(velocityProgram, "HandDistance");
		for (int i = 0; i < bSamplers.length; i++) {
			bSamplers[i] = GL20.glGetUniformLocation(blendProgram, "Sample" + i + "Sampler");
		}
		bWeights0 = GL20.glGetUniformLocation(blendProgram, "Weights0");
		bWeights1 = GL20.glGetUniformLocation(blendProgram, "Weights1");
		bWeights2 = GL20.glGetUniformLocation(blendProgram, "Weights2");
		bInvTotal = GL20.glGetUniformLocation(blendProgram, "InvTotalWeight");
		bCount = GL20.glGetUniformLocation(blendProgram, "ActiveCount");
		aIn = GL20.glGetUniformLocation(accumProgram, "InSampler");
		aPrev = GL20.glGetUniformLocation(accumProgram, "PrevSampler");
		aBlend = GL20.glGetUniformLocation(accumProgram, "BlendFactor");
		aMax = GL20.glGetUniformLocation(accumProgram, "UseMax");
		aDither = GL20.glGetUniformLocation(accumProgram, "DitherOffset");
		return true;
	}

	private static int shader(int type, String file) {
		int shader = GL20.glCreateShader(type);
		GL20.glShaderSource(shader, source(file));
		GL20.glCompileShader(shader);
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			String log = GL20.glGetShaderInfoLog(shader, 4096);
			GL20.glDeleteShader(shader);
			throw new IllegalStateException("couldn't compile " + file + ": " + log);
		}
		return shader;
	}

	private static int link(int vertex, String fragmentFile) {
		int fragment = shader(GL20.GL_FRAGMENT_SHADER, fragmentFile);
		int program = GL20.glCreateProgram();
		GL20.glAttachShader(program, vertex);
		GL20.glAttachShader(program, fragment);
		GL20.glLinkProgram(program);
		GL20.glDeleteShader(fragment);
		if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
			String log = GL20.glGetProgramInfoLog(program, 4096);
			GL20.glDeleteProgram(program);
			throw new IllegalStateException("couldn't link " + fragmentFile + ": " + log);
		}
		return program;
	}

	private static String source(String file) {
		String path = "assets/flintclient/shaders/gl120/" + file;
		try (InputStream in = MotionBlur.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("missing " + path);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			for (int n; (n = in.read(buffer)) > 0;) {
				out.write(buffer, 0, n);
			}
			return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("couldn't read " + path, e);
		}
	}

	// ---------- releasing ----------

	private void releaseTextures() {
		for (int id : new int[] {source, trail, depth}) {
			if (id > 0) {
				GlStateManager.deleteTextures(id);
			}
		}
		source = -1;
		trail = -1;
		depth = -1;
		texW = 0;
		texH = 0;
		accumValid = false;
	}

	private void releaseHistory() {
		for (int i = 0; i < history.length; i++) {
			if (history[i] > 0) {
				GlStateManager.deleteTextures(history[i]);
			}
			history[i] = 0;
		}
		historyW = 0;
		historyH = 0;
		historyFilled = 0;
		historyWrite = 0;
	}

	private void release() {
		releaseTextures();
		releaseHistory();
		previousReady = false;
	}
}
