package com.flintclient.client.control.fx;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.platform.HookGuard;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * Optional gameplay motion blur: each frame keeps a share of the previous image of the world, so fast camera
 * turns leave a short trail. Off by default; purely visual (nothing here reads or changes game state).
 *
 * <p><b>Where:</b> {@code mixin/fx/MotionBlurMixin} calls {@link #beforeHand} from {@code GameRenderer.renderLevel}
 * just before the hand and screen overlays are drawn: the world (terrain, entities, particles, sky, weather) is
 * complete in the main target, while the hand, held item, fire/pumpkin/water overlays, glowing outlines,
 * Minecraft's post effects and the whole GUI come later and stay sharp.
 *
 * <p><b>How (per frame, when on):</b> one full-screen draw blends the kept image ({@code history}) over the new
 * world image in the main target with weight {@link BlurMath#retention}, then one texture copy stores the result
 * as the next frame's history. One extra RGBA8 texture the size of the window (16.4 MB at 2560×1600); no depth.
 * Off: a single field read per frame, and the texture is released.
 *
 * <p><b>Restarts</b> (the history is reseeded from the current frame, no blend): first frame after turning it on,
 * a window resize, a new world or dimension, more than {@link BlurMath#MAX_GAP_NANOS} since the last blended frame
 * (Dynamic FPS skipping frames, a stall, menus without the world), and panorama screenshots (Minecraft renders
 * those through {@code renderLevel} too; they're never blurred).
 *
 * <p><b>Failures:</b> any exception, or the shader not compiling on this driver, turns the effect off for the
 * session with one log line ({@link HookGuard}); the game keeps running unblurred. The pipeline is Flint's own, so
 * a failure never goes through Minecraft's post-effect loader (whose compile errors trigger resource-pack recovery).
 * Render thread only.
 */
public final class MotionBlur {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "post/motion_blur");
	private static final int CONFIG_SIZE = 16;
	private static final HookGuard GUARD = new HookGuard("motion blur", MotionBlur::turnedOff);

	private static boolean enabled;
	private static float strength = 0.5f;

	private static @Nullable RenderPipeline pipeline;
	private static @Nullable RenderTarget history;
	private static @Nullable GpuBuffer config;
	private static boolean historyValid;
	private static long lastFrameNanos;
	private static int levelIdentity;
	private static int seed;

	private MotionBlur() {
	}

	/** Turns the effect on or off. Off releases the history texture (now if on the render thread, else next frame). */
	public static void setEnabled(boolean on) {
		enabled = on;
		if (!on && RenderSystem.isOnRenderThread()) {
			release();
		}
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Strength from 0 (no trail) to 1 (a trail of about an eighth of a second); values outside are clamped. */
	public static void setStrength(float value) {
		strength = BlurMath.clampStrength(value);
	}

	public static float strength() {
		return strength;
	}

	/**
	 * From the start of every client tick: frees the kept frame once no world frame has been drawn for over a second
	 * (the title screen, a long menu), so the texture never outlives the world it belongs to. One field compare
	 * otherwise.
	 */
	public static void tick() {
		if (history != null && System.nanoTime() - lastFrameNanos > 1_000_000_000L && RenderSystem.isOnRenderThread()) {
			release();
		}
	}

	/** False once the effect has failed on this run (it then stays off until restart). */
	public static boolean available() {
		return GUARD.on();
	}

	/**
	 * From {@code GameRenderer.renderLevel}, after the world and before the hand. {@code panoramic} is the camera's
	 * panorama-screenshot mode.
	 */
	public static void beforeHand(RenderTarget main, boolean panoramic) {
		if (!enabled || strength == 0 || !GUARD.on()) {
			if (history != null) {
				release();
			}
			return;
		}
		try {
			apply(main, panoramic);
		} catch (RuntimeException e) {
			GUARD.fail(e);
			release();
		}
	}

	private static void apply(RenderTarget main, boolean panoramic) {
		long now = System.nanoTime();
		long gap = now - lastFrameNanos;
		lastFrameNanos = now;
		GpuTexture color = main.getColorTexture();
		GpuTextureView colorView = main.getColorTextureView();
		if (panoramic || color == null || colorView == null) {
			historyValid = false;
			return;
		}
		int width = color.getWidth(0);
		int height = color.getHeight(0);
		int level = System.identityHashCode(Minecraft.getInstance().level);
		if (level != levelIdentity || !BlurMath.continues(gap)) {
			levelIdentity = level;
			historyValid = false;
		}
		RenderTarget kept = history;
		if (kept == null || kept.width != width || kept.height != height) {
			if (kept != null) {
				kept.destroyBuffers();
			}
			kept = new TextureTarget("Flint motion blur", width, height, GpuFormat.RGBA8_UNORM, null);
			history = kept;
			historyValid = false;
		}
		GpuTexture keptColor = kept.getColorTexture();
		GpuTextureView keptView = kept.getColorTextureView();
		if (keptColor == null || keptView == null) {
			throw new IllegalStateException("motion blur history has no colour texture");
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		float retention = BlurMath.retention(strength, gap);
		if (historyValid && retention > 0) {
			blend(encoder, colorView, keptView, retention);
		}
		encoder.copyTextureToTexture(color, keptColor, 0, 0, 0, 0, 0, width, height);
		historyValid = true;
	}

	private static void blend(CommandEncoder encoder, GpuTextureView target, GpuTextureView kept, float retention) {
		CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline());
		if (compiled == null) {
			// Minecraft's cache doesn't remember failures, so this must not be retried every frame.
			throw new IllegalStateException("couldn't compile " + SHADER);
		}
		GpuBuffer buffer = config;
		if (buffer == null) {
			buffer = RenderSystem.getDevice().createBuffer(() -> "Flint motion blur config", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, CONFIG_SIZE);
			config = buffer;
		}
		seed = (seed + 1) & 255;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			encoder.writeToBuffer(buffer.slice(), Std140Builder.onStack(stack, CONFIG_SIZE).putFloat(retention).putFloat(seed).get());
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "Flint motion blur", target, Optional.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("MotionBlurConfig", buffer);
			pass.setUniform("InSampler", kept, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	/** Flint's blend pipeline, built on first use (Minecraft compiles it then, from Flint's shader in its assets). */
	private static RenderPipeline pipeline() {
		RenderPipeline p = pipeline;
		if (p == null) {
			p = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "pipeline/motion_blur"))
				.withVertexShader("core/screenquad")
				.withFragmentShader(SHADER)
				.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("MotionBlurConfig", UniformType.UNIFORM_BUFFER)
					.build())
				// previous × alpha + current × (1 − alpha) on colour; the main target's alpha is left as it is
				.withColorTargetState(new ColorTargetState(
					Optional.of(new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE)),
					GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
				.build();
			pipeline = p;
		}
		return p;
	}

	private static void release() {
		RenderTarget kept = history;
		history = null;
		historyValid = false;
		if (kept != null) {
			kept.destroyBuffers();
		}
		GpuBuffer buffer = config;
		config = null;
		if (buffer != null) {
			buffer.close();
		}
	}

	private static void turnedOff(String path, RuntimeException error) {
		FlintClient.LOGGER.warn("Flint's motion blur stopped working and is off until Minecraft restarts", error);
	}
}
