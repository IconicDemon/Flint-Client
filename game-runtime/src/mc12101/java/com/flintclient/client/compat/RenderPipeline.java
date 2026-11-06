package com.flintclient.client.compat;

/**
 * Minecraft 1.21.1 has no render pipelines (26.x's GPU state objects): the GUI picks its shader and blending as it draws.
 * The shared sources hand GuiGraphics a pipeline ({@link RenderPipelines}); on 1.21.1 it only says how a quad blends.
 */
public final class RenderPipeline {
	private final String name;
	private final boolean invert;

	RenderPipeline(String name, boolean invert) {
		this.name = name;
		this.invert = invert;
	}

	/** Drawn inverting what is under it (the crosshair, text selection), not blended over it. */
	public boolean invert() {
		return invert;
	}

	@Override
	public String toString() {
		return name;
	}
}
