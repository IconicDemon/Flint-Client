package com.flintclient.client.mc;

/** Newer Minecraft's GUI drawing pipelines, for Flint's main menu on Minecraft 1.8.9 (see {@link GuiGraphicsExtractor#blit}). */
public final class RenderPipelines {
	/** A textured GUI quad, alpha-blended (1.8.9's GUI state). */
	public static final Object GUI_TEXTURED = new Object();

	private RenderPipelines() {
	}
}
