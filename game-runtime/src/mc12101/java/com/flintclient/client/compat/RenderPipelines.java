package com.flintclient.client.compat;

/** The GUI pipelines the shared sources name, as 1.21.1 draws them ({@link RenderPipeline}). */
public final class RenderPipelines {
	/** Textured, tinted, blended over what is under it. */
	public static final RenderPipeline GUI_TEXTURED = new RenderPipeline("gui_textured", false);
	/** The crosshair: inverts what is under it. */
	public static final RenderPipeline CROSSHAIR = new RenderPipeline("crosshair", true);
	/** A fill that inverts what is under it. */
	public static final RenderPipeline GUI_INVERT = new RenderPipeline("gui_invert", true);
	/** A plain coloured fill. */
	public static final RenderPipeline GUI = new RenderPipeline("gui", false);

	private RenderPipelines() {
	}
}
