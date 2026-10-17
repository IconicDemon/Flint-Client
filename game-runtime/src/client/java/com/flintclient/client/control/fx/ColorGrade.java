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
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

/**
 * The Color Saturation module's colour grading: hue, saturation, brightness, contrast and greyscale over the finished
 * world image (the world, the hand and Minecraft's own post effects), before the GUI is drawn, so menus, the HUD and chat
 * keep their true colours.
 *
 * <p><b>How (per frame, when any setting is away from 0):</b> one texture copy of the main target's colour into a texture
 * the size of the window (16.4 MB at 2560x1600), then one full-screen draw back into the main target with Flint's shader.
 * At the neutral settings nothing runs and the texture is released. The pipeline is compiled once, on first use.
 *
 * <p><b>Failures:</b> any exception, or the shader not compiling on this driver, turns the effect off for the session with
 * one log line ({@link HookGuard}); the game keeps running with its normal colours. Render thread only.
 */
public final class ColorGrade {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "post/color_grade");
	private static final int CONFIG_SIZE = 32;
	private static final HookGuard GUARD = new HookGuard("color saturation",
		(path, error) -> FlintClient.LOGGER.warn("Flint's color saturation stopped working and is off until Minecraft restarts", error));

	private static float hue;
	private static float saturation;
	private static float brightness;
	private static float contrast;
	private static boolean grayscale;
	private static boolean enabled;

	private static @Nullable RenderPipeline pipeline;
	private static @Nullable RenderTarget copy;
	private static @Nullable GpuBuffer config;

	private ColorGrade() {
	}

	/**
	 * The grading: hue in degrees (-180 to 180), the others from -1 to 1. All 0 and not grey is no change at all (and costs
	 * nothing).
	 */
	public static void set(boolean on, float hueDegrees, float saturationAmount, float brightnessAmount, float contrastAmount, boolean grey) {
		enabled = on;
		hue = (float) Math.toRadians(hueDegrees);
		saturation = clamp(saturationAmount);
		brightness = clamp(brightnessAmount);
		contrast = clamp(contrastAmount);
		grayscale = grey;
	}

	static float clamp(float v) {
		return Float.isNaN(v) ? 0 : Math.max(-1, Math.min(1, v));
	}

	/** Whether the settings change anything. */
	public static boolean active() {
		return enabled && (hue != 0 || saturation != 0 || brightness != 0 || contrast != 0 || grayscale);
	}

	public static boolean available() {
		return GUARD.on();
	}

	/** From {@code GameRenderer.render}, after the world and Minecraft's post effects, before the GUI. */
	public static void afterWorld(RenderTarget main) {
		if (!active() || !GUARD.on()) {
			if (copy != null) {
				release();
			}
			return;
		}
		try {
			apply(main);
		} catch (RuntimeException e) {
			GUARD.fail(e);
			release();
		}
	}

	private static void apply(RenderTarget main) {
		GpuTexture color = main.getColorTexture();
		GpuTextureView colorView = main.getColorTextureView();
		if (color == null || colorView == null) {
			return;
		}
		int width = color.getWidth(0);
		int height = color.getHeight(0);
		RenderTarget kept = copy;
		if (kept == null || kept.width != width || kept.height != height) {
			if (kept != null) {
				kept.destroyBuffers();
			}
			kept = new TextureTarget("Flint color grade", width, height, GpuFormat.RGBA8_UNORM, null);
			copy = kept;
		}
		GpuTexture keptColor = kept.getColorTexture();
		GpuTextureView keptView = kept.getColorTextureView();
		if (keptColor == null || keptView == null) {
			throw new IllegalStateException("color grade copy has no colour texture");
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToTexture(color, keptColor, 0, 0, 0, 0, 0, width, height);
		CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline());
		if (compiled == null) {
			throw new IllegalStateException("couldn't compile " + SHADER);
		}
		GpuBuffer buffer = config;
		if (buffer == null) {
			buffer = RenderSystem.getDevice().createBuffer(() -> "Flint color grade config", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				CONFIG_SIZE);
			config = buffer;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			encoder.writeToBuffer(buffer.slice(), Std140Builder.onStack(stack, CONFIG_SIZE).putFloat(hue).putFloat(saturation).putFloat(brightness)
				.putFloat(contrast).putFloat(grayscale ? 1 : 0).get());
		}
		try (RenderPass pass = encoder.createRenderPass(() -> "Flint color grade", colorView, Optional.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("ColorGradeConfig", buffer);
			pass.setUniform("InSampler", keptView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	private static RenderPipeline pipeline() {
		RenderPipeline p = pipeline;
		if (p == null) {
			p = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "pipeline/color_grade"))
				.withVertexShader("core/screenquad")
				.withFragmentShader(SHADER)
				.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("ColorGradeConfig", UniformType.UNIFORM_BUFFER)
					.build())
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
				.build();
			pipeline = p;
		}
		return p;
	}

	/** Frees the copy texture and the settings buffer (the effect turned to neutral or off, or failed). */
	public static void release() {
		RenderTarget kept = copy;
		copy = null;
		if (kept != null) {
			kept.destroyBuffers();
		}
		GpuBuffer buffer = config;
		config = null;
		if (buffer != null) {
			buffer.close();
		}
	}
}
