/*
 * The blur is adapted from Natural Motion Blur 1.4.5 by Pasi, with IMS212, Romain H and What42Pizza (LGPL-3.0;
 * META-INF/licenses/LGPL-3.0-NaturalMotionBlur.txt).
 */
package com.flintclient.client.control.fx;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.platform.HookGuard;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Motion Blur on Minecraft 1.21.1, exactly as on the other lines (the rules: {@link NmbTiming}; the look: Natural Motion Blur's
 * velocity based blur, frame blending, hybrid and accumulation, with the same settings). 1.21.1 has no GPU pass objects, so the
 * same shaders run as plain OpenGL programs ({@code assets/flintclient/shaders/gl}): each pass draws one screen-covering
 * triangle into a texture of Flint's own (reading Minecraft's colour and depth textures, never drawing into a texture it reads)
 * and that texture is copied back onto the world image with one framebuffer blit.
 *
 * <p><b>Where</b> (the hooks: {@code mixin/fx/MotionBlurMixin} and {@code MotionBlurLevelMixin}): {@link #beginFrame} as
 * {@code GameRenderer.renderLevel} hands the world to the level renderer (its projection with view bobbing, the hurt tilt and
 * nausea in it); {@link #early} in {@code LevelRenderer.renderLevel} after the solid and cutout terrain, before the entities and
 * block entities; {@link #late} at the {@code "hand"} profiler step, before the depth buffer is cleared for the hand. The hand,
 * the overlays, Minecraft's post effects and the GUI stay sharp.
 *
 * <p><b>State:</b> everything is bound through {@code GlStateManager} (its caches stay true), the immediate-mode vertex array
 * cache is reset before and the shader program cache after; the main target is bound again when done. Nothing is allocated per
 * frame, nothing is read back. Failures, continuity and cost as on the other lines (see their MotionBlur). Render thread only.
 */
public final class MotionBlur {
	private static final HookGuard GUARD = new HookGuard("motion blur", MotionBlur::turnedOff);
	/** Pixels nearer the eye than this (blocks) are left sharp: a hand a shader pack drew into the world. */
	private static final float HAND_DISTANCE = 0.114F;
	private static final int GL_TEXTURE0 = 0x84C0;

	// The settings.
	private static boolean moduleOn;
	private static float moduleStrength = 1;
	private static int algorithm = NmbTiming.VELOCITY;
	private static int profile;
	private static boolean refreshScaling = true;
	private static int quality = NmbTiming.BALANCED;
	private static boolean movement = true;
	private static boolean gameplayOn;
	private static float gameplayStrength = 0.5F;

	// GPU resources.
	private static int vertexArray;
	private static int velocityProgram;
	private static int blendProgram;
	private static int accumProgram;
	private static boolean programsTried;
	private static @Nullable TextureTarget output;
	private static @Nullable TextureTarget accum;
	private static @Nullable TextureTarget accumNext;
	private static final TextureTarget[] HISTORY = new TextureTarget[NmbTiming.MAX_HISTORY];

	// Uniform locations.
	private static int vIn, vDepth, vMvInverse, vProjInverse, vPrevView, vPrevProjection, vDelta, vBlend, vRes, vDensity, vMax, vProfile, vMode,
		vFar, vReversed, vDepthToNdc, vHand;
	private static final int[] B_SAMPLERS = new int[NmbTiming.MAX_HISTORY];
	private static int bWeights0, bWeights1, bWeights2, bInvTotal, bCount;
	private static int aIn, aPrev, aBlend, aMax, aDither;

	// Across frames.
	private static boolean accumValid;
	private static int ditherFrame;
	private static int lastMode = -1;
	private static final double[] TIMES = new double[HISTORY.length];
	private static int historyWrite;
	private static int historyFilled;
	private static long lastFrameNanos;
	private static long lastDrawn;
	private static float smoothedFps;
	private static int refreshRate = 60;
	private static long refreshCheckedNanos;
	private static int levelIdentity;
	private static int lastWidth;
	private static int lastHeight;
	private static boolean lastDetached;
	private static boolean previousReady;
	private static final Matrix4f PREV_VIEW = new Matrix4f();
	private static final Matrix4f PREV_PROJECTION = new Matrix4f();
	private static double prevX;
	private static double prevY;
	private static double prevZ;

	// This frame (set by beginFrame).
	private static boolean frameActive;
	private static int frameMode;
	private static float frameStrength;
	private static float frameSeconds;
	private static float fps;
	private static boolean frameDetached;
	private static boolean frameTranslating;
	private static final float[] INV_VIEW_ARRAY = new float[16];
	private static final float[] INV_PROJECTION_ARRAY = new float[16];
	private static final float[] PREV_VIEW_ARRAY = new float[16];
	private static final float[] PREV_PROJECTION_ARRAY = new float[16];
	private static boolean velocityReady;
	private static float velocityBlend;
	private static int earlyMode = NmbTiming.NONE;
	private static boolean earlyDrawn;
	private static final Matrix4f INV_VIEW = new Matrix4f();
	private static final Matrix4f INV_PROJECTION = new Matrix4f();
	private static final Matrix4f FRAME_PREV_VIEW = new Matrix4f();
	private static final Matrix4f FRAME_PREV_PROJECTION = new Matrix4f();
	private static float deltaX;
	private static float deltaY;
	private static float deltaZ;

	private MotionBlur() {
	}

	/**
	 * The Motion Blur module's settings: on, strength (0–2), algorithm and quality ({@link NmbTiming}), profile (0 uniform, 1
	 * weighted), refresh rate scaling, and Blur when moving.
	 */
	public static void configure(boolean on, float strength, int blurAlgorithm, int blurProfile, boolean scaling, int blurQuality,
		boolean blurMovement) {
		moduleOn = on;
		moduleStrength = Math.max(0, strength);
		algorithm = blurAlgorithm;
		profile = blurProfile;
		refreshScaling = scaling;
		quality = blurQuality;
		movement = blurMovement;
		releaseIfOff();
	}

	/** Flint's own gameplay blur switch (client settings): velocity blur at {@code value} (0–1 → strength 0–2). */
	public static void setEnabled(boolean on) {
		gameplayOn = on;
		releaseIfOff();
	}

	public static void setStrength(float value) {
		gameplayStrength = BlurMath.clampStrength(value);
	}

	public static boolean enabled() {
		return moduleOn || gameplayOn;
	}

	public static float strength() {
		return moduleOn ? moduleStrength : gameplayStrength * 2;
	}

	private static void releaseIfOff() {
		if (!enabled() && RenderSystem.isOnRenderThread()) {
			release();
		}
	}

	/** From the start of every client tick: asks Iris about shader packs, and frees the textures once no world frame has been drawn for over a second. */
	public static void tick() {
		if (enabled()) {
			ShaderPacks.refresh();
		}
		if (hasResources() && System.nanoTime() - lastDrawn > 1_000_000_000L && RenderSystem.isOnRenderThread()) {
			release();
		}
	}

	/** False once the effect has failed on this run (it then stays off until restart). */
	public static boolean available() {
		return GUARD.on();
	}

	// ---- The frame.

	/**
	 * As the world is about to be drawn: {@code view} and {@code projection} are this frame's camera rotation and the projection
	 * the world is drawn with, {@code camera} its position. Decides what this frame draws.
	 */
	public static void beginFrame(RenderTarget main, boolean panoramic, Matrix4fc view, Matrix4fc projection, Vec3 camera) {
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
		float strength = strength();
		if (!enabled() || strength == 0 || !GUARD.on()) {
			if (hasResources()) {
				release();
			}
			remember(view, projection, camera);
			previousReady = false;
			return;
		}
		try {
			plan(main, panoramic, view, projection, camera, strength);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			release();
			frameActive = false;
		}
		remember(view, projection, camera);
		if (panoramic) {
			// A panorama screenshot's frames turn the camera in jumps: the next ordinary frame starts over.
			previousReady = false;
		}
	}

	private static void plan(RenderTarget main, boolean panoramic, Matrix4fc view, Matrix4fc projection, Vec3 camera, float strength) {
		lastDrawn = System.nanoTime();
		if (panoramic || main.width <= 0 || main.height <= 0 || !programs()) {
			previousReady = false;
			accumValid = false;
			return;
		}
		int mode = moduleOn ? algorithm : NmbTiming.VELOCITY;
		Minecraft mc = Minecraft.getInstance();
		boolean detached = !mc.options.getCameraType().isFirstPerson() || mc.player != null && mc.player.isPassenger();
		int width = main.width;
		int height = main.height;
		int level = System.identityHashCode(mc.level);
		double dx = camera.x - prevX;
		double dy = camera.y - prevY;
		double dz = camera.z - prevZ;
		boolean continuous = previousReady && level == levelIdentity && width == lastWidth && height == lastHeight && detached == lastDetached
			&& mode == lastMode && NmbTiming.continues(frameSeconds, dx, dy, dz);
		levelIdentity = level;
		lastWidth = width;
		lastHeight = height;
		lastDetached = detached;
		if (mode != lastMode || !continuous) {
			// Kept frames and the trail start over from this frame.
			lastMode = mode;
			accumValid = false;
			historyFilled = 0;
		}
		updateRefreshRate();
		frameActive = true;
		frameMode = mode;
		frameStrength = strength;
		frameDetached = detached;
		if (!NmbTiming.usesVelocity(mode) || !continuous || dx == 0 && dy == 0 && dz == 0 && view.equals(PREV_VIEW) && projection.equals(PREV_PROJECTION)) {
			// A still camera has nothing to blur: no velocity pass at all.
			return;
		}
		view.invert(INV_VIEW);
		projection.invert(INV_PROJECTION);
		FRAME_PREV_VIEW.set(PREV_VIEW);
		FRAME_PREV_PROJECTION.set(PREV_PROJECTION);
		deltaX = (float) dx;
		deltaY = (float) dy;
		deltaZ = (float) dz;
		velocityBlend = mode == NmbTiming.HYBRID ? NmbTiming.hybridFiller(strength, fps, smoothedFps, refreshRate, historyFilled)
			: NmbTiming.velocityStrength(strength, fps, refreshRate, refreshScaling);
		boolean translating = dx != 0 || dy != 0 || dz != 0;
		if (!translating && NmbTiming.turnPixels(INV_PROJECTION.get(INV_PROJECTION_ARRAY), INV_VIEW.get(INV_VIEW_ARRAY),
			FRAME_PREV_VIEW.get(PREV_VIEW_ARRAY), FRAME_PREV_PROJECTION.get(PREV_PROJECTION_ARRAY), turnDepthNdc(), width, height) * velocityBlend < 0.5F) {
			// The camera only drifted (a field of view settling, a hair of mouse movement): under half a pixel, nothing to draw.
			return;
		}
		frameTranslating = translating;
		velocityReady = velocityBlend > 0;
		earlyMode = ShaderPacks.active() ? NmbTiming.NONE : NmbTiming.earlyMode(movement, detached, translating);
	}

	private static void remember(Matrix4fc view, Matrix4fc projection, Vec3 camera) {
		PREV_VIEW.set(view);
		PREV_PROJECTION.set(projection);
		prevX = camera.x;
		prevY = camera.y;
		prevZ = camera.z;
		previousReady = true;
	}

	/** Whether this frame wants the early pass (between the terrain and the entities). */
	public static boolean wantsEarly() {
		return frameActive && velocityReady && !earlyDrawn && earlyMode != NmbTiming.NONE && GUARD.on();
	}

	/** After the solid and cutout terrain, before the entities: the terrain's blur ({@link NmbTiming#earlyMode}). */
	public static void early(RenderTarget main) {
		if (!wantsEarly()) {
			return;
		}
		try {
			begin();
			velocity(main, earlyMode);
			earlyDrawn = true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			release();
			frameActive = false;
		} finally {
			end(main);
		}
	}

	/** After the world and before the hand: the camera's blur, then frame blending or the trail. */
	public static void late(RenderTarget main) {
		if (!frameActive || !GUARD.on()) {
			return;
		}
		frameActive = false;
		if (!velocityReady && !NmbTiming.usesFrameBlending(frameMode) && !NmbTiming.usesAccumulation(frameMode)) {
			// A still camera with velocity blur: nothing to draw.
			return;
		}
		try {
			begin();
			if (velocityReady) {
				int mode = NmbTiming.lateMode(movement, frameDetached, earlyDrawn, frameTranslating);
				if (mode != NmbTiming.NONE) {
					velocity(main, mode);
				}
			}
			if (NmbTiming.usesFrameBlending(frameMode)) {
				frameBlend(main, frameStrength);
			} else if (NmbTiming.usesAccumulation(frameMode)) {
				accumulate(main, NmbTiming.kept(frameMode, frameStrength, frameSeconds, refreshRate), frameMode == NmbTiming.ACCUMULATION_MAX);
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			release();
		} finally {
			end(main);
		}
	}

	private static void updateRefreshRate() {
		long now = System.nanoTime();
		if (now - refreshCheckedNanos < 1_000_000_000L) {
			return;
		}
		refreshCheckedNanos = now;
		refreshRate = Math.max(1, Minecraft.getInstance().getWindow().getRefreshRate());
	}

	/** A clip-space depth halfway through the depth range (1.21.1: -1 to 1), for NmbTiming.turnPixels. */
	private static float turnDepthNdc() {
		return 0;
	}

	// ---- Velocity.

	private static void velocity(RenderTarget main, int mode) {
		int width = main.width;
		int height = main.height;
		TextureTarget out = outputTarget(width, height);
		drawInto(out);
		GlStateManager._glUseProgram(velocityProgram);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer matrix = stack.mallocFloat(16);
			GlStateManager._glUniformMatrix4(vMvInverse, false, INV_VIEW.get(matrix));
			GlStateManager._glUniformMatrix4(vProjInverse, false, INV_PROJECTION.get(matrix));
			GlStateManager._glUniformMatrix4(vPrevView, false, FRAME_PREV_VIEW.get(matrix));
			GlStateManager._glUniformMatrix4(vPrevProjection, false, FRAME_PREV_PROJECTION.get(matrix));
		}
		GL20.glUniform3f(vDelta, deltaX, deltaY, deltaZ);
		GL20.glUniform1f(vBlend, velocityBlend);
		GL20.glUniform2f(vRes, width, height);
		GL20.glUniform1f(vDensity, NmbTiming.sampleDensity(quality));
		GlStateManager._glUniform1i(vMax, NmbTiming.maxSamples(quality));
		GlStateManager._glUniform1i(vProfile, profile);
		GlStateManager._glUniform1i(vMode, mode);
		// 1.21.1 draws with ordinary depth (far is 1), from -1 to 1 in clip space.
		GL20.glUniform1f(vFar, 1);
		GL20.glUniform1f(vReversed, 0);
		GL20.glUniform2f(vDepthToNdc, 2, -1);
		GL20.glUniform1f(vHand, HAND_DISTANCE);
		GlStateManager._glUniform1i(vIn, 0);
		GlStateManager._glUniform1i(vDepth, 1);
		bindTexture(0, main.getColorTextureId(), GL11.GL_LINEAR);
		bindTexture(1, main.getDepthTextureId(), GL11.GL_NEAREST);
		drawTriangle();
		restoreNearest(0, main.getColorTextureId());
		blit(out.frameBufferId, main.frameBufferId, width, height);
	}

	// ---- Frame blending.

	private static final int[] SLOTS = new int[NmbTiming.MAX_HISTORY];
	private static final float[] WEIGHTS = new float[NmbTiming.MAX_HISTORY];
	private static final int[] ORDER = new int[HISTORY.length];

	private static void frameBlend(RenderTarget main, float strength) {
		int width = main.width;
		int height = main.height;
		TextureTarget slot = HISTORY[historyWrite];
		if (slot == null || slot.width != width || slot.height != height) {
			for (int i = 0; i < HISTORY.length; i++) {
				if (HISTORY[i] != null) {
					HISTORY[i].destroyBuffers();
				}
				HISTORY[i] = newTarget(width, height);
			}
			historyFilled = 0;
			historyWrite = 0;
			slot = HISTORY[0];
		}
		blit(main.frameBufferId, slot.frameBufferId, width, height);
		double now = System.nanoTime() * 1.0E-9;
		TIMES[historyWrite] = now;
		historyWrite = (historyWrite + 1) % HISTORY.length;
		historyFilled = Math.min(historyFilled + 1, HISTORY.length);
		int first = historyWrite - historyFilled;
		for (int i = 0; i < historyFilled; i++) {
			ORDER[i] = Math.floorMod(first + i, HISTORY.length);
		}
		int count = NmbTiming.weights(now, TIMES, ORDER, historyFilled, smoothedFps, refreshRate, strength, SLOTS, WEIGHTS);
		if (count <= 1) {
			return;
		}
		float total = 0;
		for (int i = 0; i < count; i++) {
			total += WEIGHTS[i];
		}
		TextureTarget out = outputTarget(width, height);
		drawInto(out);
		GlStateManager._glUseProgram(blendProgram);
		GL20.glUniform4f(bWeights0, WEIGHTS[0], WEIGHTS[1], WEIGHTS[2], WEIGHTS[3]);
		GL20.glUniform4f(bWeights1, WEIGHTS[4], WEIGHTS[5], WEIGHTS[6], WEIGHTS[7]);
		GL20.glUniform4f(bWeights2, WEIGHTS[8], WEIGHTS[9], WEIGHTS[10], WEIGHTS[11]);
		GL20.glUniform1f(bInvTotal, total > 0 ? 1 / total : 1);
		GlStateManager._glUniform1i(bCount, count);
		for (int i = 0; i < NmbTiming.MAX_HISTORY; i++) {
			GlStateManager._glUniform1i(B_SAMPLERS[i], i);
			bindTexture(i, HISTORY[SLOTS[Math.min(i, count - 1)]].getColorTextureId(), GL11.GL_NEAREST);
		}
		drawTriangle();
		blit(out.frameBufferId, main.frameBufferId, width, height);
	}

	// ---- Accumulation.

	/** The fading trail, as on the other lines: drawn into the second trail target, copied onto the world, then the trail. */
	private static void accumulate(RenderTarget main, float kept, boolean max) {
		int width = main.width;
		int height = main.height;
		TextureTarget prev = accum;
		TextureTarget next = accumNext;
		if (prev == null || next == null || prev.width != width || prev.height != height || next.width != width || next.height != height) {
			releaseTrail();
			prev = newTarget(width, height);
			next = newTarget(width, height);
			accum = prev;
			accumNext = next;
		}
		if (!accumValid || !(kept > 0)) {
			// A fresh trail starts as this frame.
			blit(main.frameBufferId, prev.frameBufferId, width, height);
			accumValid = true;
			return;
		}
		ditherFrame = (ditherFrame + 1) & 1023;
		drawInto(next);
		GlStateManager._glUseProgram(accumProgram);
		GL20.glUniform1f(aBlend, kept);
		GlStateManager._glUniform1i(aMax, max ? 1 : 0);
		GL20.glUniform1f(aDither, ditherFrame * 5.588238F);
		GlStateManager._glUniform1i(aIn, 0);
		GlStateManager._glUniform1i(aPrev, 1);
		bindTexture(0, main.getColorTextureId(), GL11.GL_NEAREST);
		bindTexture(1, prev.getColorTextureId(), GL11.GL_NEAREST);
		drawTriangle();
		blit(next.frameBufferId, main.frameBufferId, width, height);
		accum = next;
		accumNext = prev;
	}

	// ---- OpenGL.

	/** Before a hook's passes: no immediate-mode vertex array left bound, nothing tested, blended or culled. */
	private static void begin() {
		BufferUploader.invalidate();
		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);
		GlStateManager._disableBlend();
		GlStateManager._disableCull();
		GlStateManager._colorMask(true, true, true, true);
	}

	/** After them: Minecraft's usual state, its program cache reset, the main target bound again. */
	private static void end(RenderTarget main) {
		GlStateManager._glBindVertexArray(0);
		ShaderInstance any = GameRenderer.getPositionTexShader();
		if (any != null) {
			// Resets ShaderInstance's own record of the bound program (and binds none).
			any.clear();
		} else {
			GlStateManager._glUseProgram(0);
		}
		GlStateManager._activeTexture(GL_TEXTURE0);
		GlStateManager._depthMask(true);
		GlStateManager._enableDepthTest();
		GlStateManager._enableCull();
		main.bindWrite(true);
	}

	private static void drawInto(TextureTarget target) {
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
		GlStateManager._viewport(0, 0, target.width, target.height);
	}

	private static void bindTexture(int unit, int texture, int filter) {
		GlStateManager._activeTexture(GL_TEXTURE0 + unit);
		GlStateManager._bindTexture(texture);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
	}

	/** The main colour texture back to the nearest filtering Minecraft keeps it at. */
	private static void restoreNearest(int unit, int texture) {
		GlStateManager._activeTexture(GL_TEXTURE0 + unit);
		GlStateManager._bindTexture(texture);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
	}

	private static void drawTriangle() {
		GlStateManager._glBindVertexArray(vertexArray);
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
	}

	private static void blit(int from, int to, int width, int height) {
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
		GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
	}

	private static TextureTarget outputTarget(int width, int height) {
		TextureTarget out = output;
		if (out == null || out.width != width || out.height != height) {
			if (out != null) {
				out.destroyBuffers();
			}
			out = newTarget(width, height);
			output = out;
		}
		return out;
	}

	private static TextureTarget newTarget(int width, int height) {
		TextureTarget target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
		target.setClearColor(0, 0, 0, 0);
		return target;
	}

	/** Compiles the three programs once; false (and the effect off) if this driver can't. */
	private static boolean programs() {
		if (programsTried) {
			return velocityProgram != 0;
		}
		programsTried = true;
		int vertex = shader(GL20.GL_VERTEX_SHADER, "nmb_screen.vsh");
		try {
			velocityProgram = link(vertex, "nmb_velocity.fsh");
			blendProgram = link(vertex, "nmb_frame_blend.fsh");
			accumProgram = link(vertex, "nmb_accumulate.fsh");
		} finally {
			GlStateManager.glDeleteShader(vertex);
		}
		vertexArray = GlStateManager._glGenVertexArrays();
		vIn = location(velocityProgram, "InSampler");
		vDepth = location(velocityProgram, "DepthSampler");
		vMvInverse = location(velocityProgram, "MvInverse");
		vProjInverse = location(velocityProgram, "ProjInverse");
		vPrevView = location(velocityProgram, "PrevModelView");
		vPrevProjection = location(velocityProgram, "PrevProjection");
		vDelta = location(velocityProgram, "CameraDelta");
		vBlend = location(velocityProgram, "BlendFactor");
		vRes = location(velocityProgram, "ViewRes");
		vDensity = location(velocityProgram, "SampleDensity");
		vMax = location(velocityProgram, "MaxSamples");
		vProfile = location(velocityProgram, "BlurProfile");
		vMode = location(velocityProgram, "Mode");
		vFar = location(velocityProgram, "FarDepth");
		vReversed = location(velocityProgram, "Reversed");
		vDepthToNdc = location(velocityProgram, "DepthToNdc");
		vHand = location(velocityProgram, "HandDistance");
		for (int i = 0; i < B_SAMPLERS.length; i++) {
			B_SAMPLERS[i] = location(blendProgram, "Sample" + i + "Sampler");
		}
		bWeights0 = location(blendProgram, "Weights0");
		bWeights1 = location(blendProgram, "Weights1");
		bWeights2 = location(blendProgram, "Weights2");
		bInvTotal = location(blendProgram, "InvTotalWeight");
		bCount = location(blendProgram, "ActiveCount");
		aIn = location(accumProgram, "InSampler");
		aPrev = location(accumProgram, "PrevSampler");
		aBlend = location(accumProgram, "BlendFactor");
		aMax = location(accumProgram, "UseMax");
		aDither = location(accumProgram, "DitherOffset");
		return true;
	}

	private static int location(int program, String name) {
		return GlStateManager._glGetUniformLocation(program, name);
	}

	private static int shader(int type, String file) {
		int shader = GlStateManager.glCreateShader(type);
		GL20.glShaderSource(shader, source(file));
		GlStateManager.glCompileShader(shader);
		if (GlStateManager.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
			String log = GL20.glGetShaderInfoLog(shader, 4096);
			GlStateManager.glDeleteShader(shader);
			throw new IllegalStateException("couldn't compile " + file + ": " + log);
		}
		return shader;
	}

	private static int link(int vertex, String fragmentFile) {
		int fragment = shader(GL20.GL_FRAGMENT_SHADER, fragmentFile);
		int program = GlStateManager.glCreateProgram();
		GlStateManager.glAttachShader(program, vertex);
		GlStateManager.glAttachShader(program, fragment);
		GL30.glBindFragDataLocation(program, 0, "fragColor");
		GlStateManager.glLinkProgram(program);
		GlStateManager.glDeleteShader(fragment);
		if (GlStateManager.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
			String log = GL20.glGetProgramInfoLog(program, 4096);
			GlStateManager.glDeleteProgram(program);
			throw new IllegalStateException("couldn't link " + fragmentFile + ": " + log);
		}
		return program;
	}

	private static String source(String file) {
		String path = "assets/" + FlintClient.MOD_ID + "/shaders/gl/" + file;
		try (InputStream in = MotionBlur.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("missing " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("couldn't read " + path, e);
		}
	}

	// ---- Shared.

	private static boolean hasResources() {
		return output != null || accum != null || HISTORY[0] != null;
	}

	private static void releaseTrail() {
		if (accum != null) {
			accum.destroyBuffers();
			accum = null;
		}
		if (accumNext != null) {
			accumNext.destroyBuffers();
			accumNext = null;
		}
		accumValid = false;
	}

	private static void release() {
		if (output != null) {
			output.destroyBuffers();
			output = null;
		}
		releaseTrail();
		for (int i = 0; i < HISTORY.length; i++) {
			if (HISTORY[i] != null) {
				HISTORY[i].destroyBuffers();
				HISTORY[i] = null;
			}
		}
		historyFilled = 0;
		historyWrite = 0;
		previousReady = false;
	}

	private static void turnedOff(String path, Throwable error) {
		FlintClient.LOGGER.warn("Flint's motion blur stopped working and is off until Minecraft restarts", error);
	}
}
