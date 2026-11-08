/*
 * Smooth is adapted from AFKZ Motion Blur 1.0 by Neerajisafk (MIT; META-INF/licenses/MIT-AFKZMotionBlur.txt). Natural is adapted
 * from Natural Motion Blur 1.4.5 by Pasi, with IMS212, Romain H and What42Pizza (LGPL-3.0;
 * META-INF/licenses/LGPL-3.0-NaturalMotionBlur.txt).
 */
package com.flintclient.client.control.fx;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.platform.HookGuard;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Motion Blur's two modes ({@link BlurRules}): Smooth, a trail that keeps a share of the last blended frame under each new one
 * (AFKZ Motion Blur's and Lunar Client's look, fading by time so it is the same at any frame rate), and Natural, Natural Motion
 * Blur's velocity blur (each pixel blurred along its own motion since the last frame, from the depth buffer and both frames'
 * camera matrices; the rules: {@link NmbTiming}). With a shader pack on, Natural blurs as Smooth. Purely visual.
 *
 * <p><b>Where</b> (the hooks: {@code mixin/fx/MotionBlurMixin} and {@code MotionBlurLevelMixin}):
 * <ol>
 * <li>{@link #beginFrame}, as {@code GameRenderer.renderLevel} hands the world to the level renderer: this frame's time, camera
 * and the projection the world is drawn with (view bobbing included, as Natural Motion Blur reads it).</li>
 * <li>{@link #early}, in the level renderer between the solid terrain and the solid features (players, mobs, block entities):
 * the terrain blurred by moving through the world (third person or riding: by all of its motion). This version draws the solid
 * terrain in a render pass of its own ({@code ChunkSectionsToRender.renderGroup} for the opaque layers), so the blur runs as that
 * returns, with no pass open.</li>
 * <li>{@link #late}, just before {@code renderLevel} clears the depth for the hand: the finished world blurred by the camera's
 * turn (all of the motion when the early pass didn't run), or Smooth's trail. The hand, the screen overlays,
 * glowing outlines, Minecraft's post effects and the GUI come after and stay sharp.</li>
 * </ol>
 *
 * <p><b>Cost:</b> Natural: per pass, one copy of the image and one full-screen pass, only while the camera moves; the samples
 * follow each pixel's blur length. Smooth: one pass and one copy, and two textures the size of the window. Off: nothing, and the
 * textures are released. Nothing is allocated per frame and nothing waits on the GPU.
 *
 * <p><b>Continuity:</b> the first frame, a pause of over a quarter second, a teleport, a resize or fullscreen switch, a new world
 * or dimension, a respawn, a screen opening or closing, pausing, the window's focus, F1, F5, a shader pack turned on or off and a
 * panorama screenshot start the blur over ({@link BlurRules.Scene}, {@link NmbTiming#continues}), as AFKZ Motion Blur resets its
 * history. A shader pack (Iris) draws the world through its own buffers: Smooth blurs its finished image, and Natural, whose depth
 * buffer no longer describes that image, blurs as Smooth.
 *
 * <p><b>Failures:</b> any exception, or a shader not compiling on this driver, turns the effect off for the session with one log
 * line ({@link HookGuard}); the game keeps running unblurred. Render thread only.
 */
public final class MotionBlur {
	private static final HookGuard GUARD = new HookGuard("motion blur", MotionBlur::turnedOff);
	private static final int VELOCITY_CONFIG_SIZE = 320;
	private static final int ACCUM_CONFIG_SIZE = 16;
	/** Pixels nearer the eye than this (blocks) are left sharp: a hand a shader pack drew into the world. */
	private static final float HAND_DISTANCE = 0.114F;

	/** Natural keeps Natural Motion Blur's recommended settings: Uniform profile, its own sample density, blur when moving. */
	private static final int NATURAL_PROFILE = 0;
	private static final int NATURAL_QUALITY = NmbTiming.BALANCED;

	// The settings: the module's mode and strength (BlurRules), and Flint's older gameplay blur switch (Smooth).
	private static boolean moduleOn;
	private static int moduleMode = BlurRules.SMOOTH;
	private static int modulePercent = BlurRules.DEFAULT_STRENGTH;
	private static boolean gameplayOn;
	private static int gameplayPercent = BlurRules.DEFAULT_STRENGTH;

	// GPU resources.
	private static @Nullable RenderPipeline velocityPipeline;
	private static @Nullable RenderPipeline accumPipeline;
	private static @Nullable RenderTarget source;
	/** The trail so far ({@code accum}) and the target the next blend is drawn into; they swap every frame. */
	private static @Nullable RenderTarget accum;
	private static @Nullable RenderTarget accumNext;
	private static @Nullable GpuBuffer earlyConfig;
	private static @Nullable GpuBuffer lateConfig;
	private static @Nullable GpuBuffer accumConfig;

	// Across frames.
	private static boolean accumValid;
	private static int ditherFrame;
	private static long lastFrameNanos;
	private static long lastDrawn;
	private static int refreshRate = 60;
	private static long refreshCheckedNanos;
	private static final BlurRules.Scene SCENE = new BlurRules.Scene();
	private static boolean previousReady;
	private static final Matrix4f PREV_VIEW = new Matrix4f();
	private static final Matrix4f PREV_PROJECTION = new Matrix4f();
	private static double prevX;
	private static double prevY;
	private static double prevZ;

	// This frame (set by beginFrame).
	private static boolean frameActive;
	private static int frameMode;
	private static int framePercent;
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
	 * The Motion Blur module's settings: on, mode ({@link BlurRules#SMOOTH} or {@link BlurRules#NATURAL}) and strength (percent).
	 * Called every client tick; asks Iris about shader packs while the blur is on.
	 */
	public static void configure(boolean on, int mode, int percent) {
		moduleOn = on;
		moduleMode = mode;
		modulePercent = Math.max(0, Math.min(100, percent));
		if (enabled()) {
			ShaderPacks.refresh();
		}
		releaseIfOff();
	}

	/** Flint's own gameplay blur switch (client settings): Smooth at {@code value} (0–1, the strength's 0–100%). */
	public static void setEnabled(boolean on) {
		gameplayOn = on;
		if (enabled()) {
			ShaderPacks.refresh();
		}
		releaseIfOff();
	}

	public static void setStrength(float value) {
		gameplayPercent = Math.round(BlurMath.clampStrength(value) * 100);
	}

	public static boolean enabled() {
		return moduleOn || gameplayOn;
	}

	/** The strength in use (percent): the module's while it is on, else the gameplay switch's. */
	public static int percent() {
		return moduleOn ? modulePercent : gameplayPercent;
	}

	/** The mode chosen (before a shader pack turns Natural into Smooth). */
	public static int mode() {
		return moduleOn ? moduleMode : BlurRules.SMOOTH;
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
		frameActive = false;
		velocityReady = false;
		earlyMode = NmbTiming.NONE;
		earlyDrawn = false;
		int percent = percent();
		if (!enabled() || percent <= 0 || !GUARD.on()) {
			if (hasResources()) {
				release();
			}
			remember(view, projection, camera);
			previousReady = false;
			return;
		}
		try {
			plan(main, panoramic, view, projection, camera, percent);
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

	private static void plan(RenderTarget main, boolean panoramic, Matrix4fc view, Matrix4fc projection, Vec3 camera, int percent) {
		lastDrawn = System.nanoTime();
		GpuTexture color = main.getColorTexture();
		if (panoramic || color == null || main.getColorTextureView() == null) {
			previousReady = false;
			accumValid = false;
			SCENE.reset();
			return;
		}
		boolean shaderPack = ShaderPacks.active();
		int mode = BlurRules.effectiveMode(mode(), shaderPack);
		Minecraft mc = Minecraft.getInstance();
		boolean riding = mc.player != null && mc.player.isPassenger();
		boolean detached = !mc.options.getCameraType().isFirstPerson() || riding;
		int width = color.getWidth(0);
		int height = color.getHeight(0);
		// A screen opening or closing, pausing, focus, F1 or a shader pack: the last frame isn't one of this view (BlurRules.Scene).
		int flags = BlurRules.flags(com.flintclient.client.control.platform.Mc263ControlPlatform.currentScreen() != null, mc.isPaused(),
			mc.isWindowActive(), com.flintclient.client.compat.McCompat.hud().isHidden(), shaderPack);
		boolean sameScene = SCENE.same(System.identityHashCode(mc.level), System.identityHashCode(mc.player), width, height,
			mc.options.getCameraType().ordinal() * 2 + (riding ? 1 : 0), mode, flags);
		double dx = camera.x - prevX;
		double dy = camera.y - prevY;
		double dz = camera.z - prevZ;
		boolean continuous = previousReady && sameScene && NmbTiming.continues(frameSeconds, dx, dy, dz);
		if (!continuous) {
			// The trail starts over from this frame.
			accumValid = false;
		}
		updateRefreshRate();
		frameActive = true;
		frameMode = mode;
		framePercent = percent;
		frameDetached = detached;
		if (mode != BlurRules.NATURAL || !continuous || dx == 0 && dy == 0 && dz == 0 && view.equals(PREV_VIEW) && projection.equals(PREV_PROJECTION)) {
			// Smooth needs no camera; a still camera has nothing to blur: no velocity pass at all.
			return;
		}
		view.invert(INV_VIEW);
		projection.invert(INV_PROJECTION);
		FRAME_PREV_VIEW.set(PREV_VIEW);
		FRAME_PREV_PROJECTION.set(PREV_PROJECTION);
		deltaX = (float) dx;
		deltaY = (float) dy;
		deltaZ = (float) dz;
		velocityBlend = NmbTiming.velocityStrength(BlurRules.naturalStrength(percent), fps, refreshRate, true);
		boolean translating = dx != 0 || dy != 0 || dz != 0;
		if (!translating && NmbTiming.turnPixels(INV_PROJECTION.get(INV_PROJECTION_ARRAY), INV_VIEW.get(INV_VIEW_ARRAY),
			FRAME_PREV_VIEW.get(PREV_VIEW_ARRAY), FRAME_PREV_PROJECTION.get(PREV_PROJECTION_ARRAY), turnDepthNdc(), width, height) * velocityBlend < 0.5F) {
			// The camera only drifted (a field of view settling, a hair of mouse movement): under half a pixel, nothing to draw.
			return;
		}
		frameTranslating = translating;
		velocityReady = velocityBlend > 0;
		earlyMode = shaderPack ? NmbTiming.NONE : NmbTiming.earlyMode(true, detached, translating);
	}

	private static void remember(Matrix4fc view, Matrix4fc projection, Vec3 camera) {
		PREV_VIEW.set(view);
		PREV_PROJECTION.set(projection);
		prevX = camera.x;
		prevY = camera.y;
		prevZ = camera.z;
		previousReady = true;
	}

	/** Whether this frame wants the early pass: the level renderer then splits its terrain pass (MotionBlurLevelMixin). */
	public static boolean wantsEarly() {
		return frameActive && velocityReady && !earlyDrawn && earlyMode != NmbTiming.NONE && GUARD.on();
	}

	/** Between the solid terrain and the solid features, with no render pass open: the terrain's blur ({@link NmbTiming#earlyMode}). */
	public static void early(RenderTarget main) {
		if (!wantsEarly()) {
			return;
		}
		try {
			velocity(RenderSystem.getDevice().createCommandEncoder(), main, earlyMode, earlyConfig());
			earlyDrawn = true;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			release();
			frameActive = false;
		}
	}

	/** After the world and before the hand: Natural's camera blur, or Smooth's trail. */
	public static void late(RenderTarget main) {
		if (!frameActive || !GUARD.on()) {
			return;
		}
		frameActive = false;
		if (!velocityReady && frameMode != BlurRules.SMOOTH) {
			// A still camera with Natural: nothing to draw.
			return;
		}
		try {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			if (velocityReady) {
				int mode = NmbTiming.lateMode(true, frameDetached, earlyDrawn, frameTranslating);
				if (mode != NmbTiming.NONE) {
					velocity(encoder, main, mode, lateConfig());
				}
			}
			if (frameMode == BlurRules.SMOOTH) {
				accumulate(encoder, main, BlurRules.smoothKept(framePercent, frameSeconds));
			}
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			release();
		}
	}

	private static void updateRefreshRate() {
		long now = System.nanoTime();
		if (now - refreshCheckedNanos < 1_000_000_000L) {
			return;
		}
		refreshCheckedNanos = now;
		refreshRate = monitorRefreshRate();
	}

	// ---- Velocity.

	private static void velocity(CommandEncoder encoder, RenderTarget main, int mode, GpuBuffer buffer) {
		GpuTextureView depth = main.getDepthTextureView();
		GpuTexture color = main.getColorTexture();
		if (depth == null || color == null) {
			return;
		}
		int width = color.getWidth(0);
		int height = color.getHeight(0);
		RenderTarget src = sourceTarget(width, height);
		encoder.copyTextureToTexture(color, src.getColorTexture(), 0, 0, 0, 0, 0, width, height);
		boolean reversed = REVERSED_DEPTH;
		boolean zeroToOne = zeroToOne();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// CameraDelta (vec3) and BlendFactor share one 16-byte slot, so they are written as one vec4: Std140Builder.putVec3
			// pads to 16 bytes on 26.2 and older.
			encoder.writeToBuffer(buffer.slice(), Std140Builder.onStack(stack, VELOCITY_CONFIG_SIZE).putMat4f(INV_VIEW).putMat4f(INV_PROJECTION)
				.putMat4f(FRAME_PREV_VIEW).putMat4f(FRAME_PREV_PROJECTION).putVec4(deltaX, deltaY, deltaZ, velocityBlend)
				.putVec2(width, height).putFloat(NmbTiming.sampleDensity(NATURAL_QUALITY)).putInt(NmbTiming.maxSamples(NATURAL_QUALITY))
				.putInt(NATURAL_PROFILE).putInt(mode).putFloat(reversed ? 0 : 1).putFloat(reversed ? 1 : 0)
				.putVec2(zeroToOne ? 1 : 2, zeroToOne ? 0 : -1).putFloat(HAND_DISTANCE).putFloat(0).get());
		}
		RenderPipeline compiled = compiled(velocityPipeline());
		try (RenderPass pass = openPass(encoder, () -> "Flint motion blur (velocity)", main.getColorTextureView())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("VelocityBlurConfig", buffer);
			bind(pass, "InSampler", src.getColorTextureView(), FilterMode.LINEAR);
			bind(pass, "DepthSampler", depth, FilterMode.NEAREST);
			draw(pass);
		}
	}

	private static GpuBuffer earlyConfig() {
		if (earlyConfig == null) {
			earlyConfig = uniformBuffer("Flint motion blur velocity (early)", VELOCITY_CONFIG_SIZE);
		}
		return earlyConfig;
	}

	private static GpuBuffer lateConfig() {
		if (lateConfig == null) {
			lateConfig = uniformBuffer("Flint motion blur velocity", VELOCITY_CONFIG_SIZE);
		}
		return lateConfig;
	}

	// ---- Accumulation.

	/**
	 * Smooth's trail: {@code kept} of the trail stays under this frame ({@link BlurRules#smoothKept}), blended in linear light.
	 * The blend reads the world image and the trail and is drawn into the second trail target, which is then copied onto the world image and becomes
	 * the trail (one pass and one copy a frame). The shader dithers its output by under half a step: an 8-bit trail that
	 * keeps most of itself every frame would otherwise round to a standstill a few steps short of the new image and leave
	 * faint ghosts and bands behind.
	 */
	private static void accumulate(CommandEncoder encoder, RenderTarget main, float kept) {
		GpuTexture color = main.getColorTexture();
		int width = color.getWidth(0);
		int height = color.getHeight(0);
		RenderTarget prev = accum;
		RenderTarget next = accumNext;
		if (prev == null || next == null || prev.width != width || prev.height != height || next.width != width || next.height != height) {
			releaseTrail();
			prev = newTarget("Flint motion blur trail", width, height);
			next = newTarget("Flint motion blur trail (next)", width, height);
			accum = prev;
			accumNext = next;
		}
		if (!accumValid || !(kept > 0)) {
			// A fresh trail starts as this frame.
			encoder.copyTextureToTexture(color, prev.getColorTexture(), 0, 0, 0, 0, 0, width, height);
			accumValid = true;
			return;
		}
		GpuBuffer buffer = accumConfig;
		if (buffer == null) {
			buffer = uniformBuffer("Flint motion blur trail", ACCUM_CONFIG_SIZE);
			accumConfig = buffer;
		}
		ditherFrame = (ditherFrame + 1) & 1023;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			encoder.writeToBuffer(buffer.slice(), Std140Builder.onStack(stack, ACCUM_CONFIG_SIZE).putFloat(kept)
				.putInt(0).putFloat(ditherFrame * 5.588238F).get());
		}
		RenderPipeline compiled = compiled(accumPipeline());
		try (RenderPass pass = openPass(encoder, () -> "Flint motion blur (accumulation)", next.getColorTextureView())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("AccumulationConfig", buffer);
			bind(pass, "InSampler", main.getColorTextureView(), FilterMode.NEAREST);
			bind(pass, "PrevSampler", prev.getColorTextureView(), FilterMode.NEAREST);
			draw(pass);
		}
		encoder.copyTextureToTexture(next.getColorTexture(), color, 0, 0, 0, 0, 0, width, height);
		accum = next;
		accumNext = prev;
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

	// ---- Shared.

	private static boolean hasResources() {
		return source != null || accum != null || earlyConfig != null || lateConfig != null || accumConfig != null;
	}

	private static RenderTarget sourceTarget(int width, int height) {
		RenderTarget src = source;
		if (src == null || src.width != width || src.height != height) {
			if (src != null) {
				src.destroyBuffers();
			}
			src = newTarget("Flint motion blur source", width, height);
			source = src;
		}
		return src;
	}

	private static GpuBuffer uniformBuffer(String name, int size) {
		return RenderSystem.getDevice().createBuffer(() -> name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size);
	}

	private static RenderPipeline compiled(RenderPipeline pipeline) {
		RenderPipeline compiled = pipeline;
		if (!RenderSystem.getDevice().precompilePipeline(pipeline).isValid()) {
			// Minecraft's cache doesn't remember failures, so this must not be retried every frame.
			throw new IllegalStateException("couldn't compile " + pipeline.getLocation());
		}
		return compiled;
	}

	private static RenderPipeline velocityPipeline() {
		if (velocityPipeline == null) {
			velocityPipeline = build("nmb_velocity", new String[] {"InSampler", "DepthSampler"}, "VelocityBlurConfig");
		}
		return velocityPipeline;
	}

	private static RenderPipeline accumPipeline() {
		if (accumPipeline == null) {
			accumPipeline = build("nmb_accumulate", new String[] {"InSampler", "PrevSampler"}, "AccumulationConfig");
		}
		return accumPipeline;
	}

	private static void release() {
		if (source != null) {
			source.destroyBuffers();
			source = null;
		}
		releaseTrail();
		for (GpuBuffer b : new GpuBuffer[] {earlyConfig, lateConfig, accumConfig}) {
			if (b != null) {
				b.close();
			}
		}
		earlyConfig = null;
		lateConfig = null;
		accumConfig = null;
		previousReady = false;
		SCENE.reset();
	}

	private static void turnedOff(String path, Throwable error) {
		FlintClient.LOGGER.warn("Flint's motion blur stopped working and is off until Minecraft restarts", error);
	}

	// ---- What differs between Minecraft versions.

	/** This version draws with ordinary depth (far is 1). */
	private static final boolean REVERSED_DEPTH = false;

	private static RenderPipeline build(String shader, String[] samplers, String config) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "pipeline/" + shader))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "post/" + shader));
		for (String sampler : samplers) {
			builder.withSampler(sampler);
		}
		return builder.withUniform(config, UniformType.UNIFORM_BUFFER)
			.withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_COLOR))
			.build();
	}

	private static void bind(RenderPass pass, String name, GpuTextureView view, FilterMode filter) {
		pass.bindTexture(name, view, RenderSystem.getSamplerCache().getClampToEdge(filter));
	}

	private static RenderTarget newTarget(String name, int width, int height) {
		return new TextureTarget(name, width, height, false);
	}

	private static RenderPass openPass(CommandEncoder encoder, java.util.function.Supplier<String> label, GpuTextureView target) {
		return encoder.createRenderPass(label, target, java.util.OptionalInt.empty());
	}

	private static void draw(RenderPass pass) {
		pass.draw(0, 3);
	}

	/** A clip-space depth halfway through the depth range (where a turn moves every depth alike), for NmbTiming.turnPixels. */
	private static float turnDepthNdc() {
		return zeroToOne() ? 0.5F : 0;
	}

	private static boolean zeroToOne() {
		return RenderSystem.getDevice().isZZeroToOne();
	}

	/** The refresh rate of the monitor the window is on. */
	private static int monitorRefreshRate() {
		return Math.max(1, Minecraft.getInstance().getWindow().getRefreshRate());
	}
}
