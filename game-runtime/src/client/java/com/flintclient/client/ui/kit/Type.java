package com.flintclient.client.ui.kit;

import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.FontFace;
import com.flintclient.client.render.ScaledFont;

/**
 * The Control Center's type scale: Inter (and Outfit for the wordmark) at exact sizes in UI units, each rasterised for
 * the interface scale it is drawn at ({@link ScaledFont}), so text is crisp at every size. Hierarchy comes from
 * these sizes and weights, not from boxes. Nothing is drawn smaller than 5 units, except {@link #OVERLINE}.
 */
public final class Type {
	private Type() {
	}

	/** Step titles in first-time setup (SemiBold 10). */
	public static final FontFace HEADLINE = ScaledFont.of(FlintFonts.HEADING, 10.0F);
	/** Page and module titles (SemiBold 9.5). */
	public static final FontFace DISPLAY = ScaledFont.of(FlintFonts.HEADING, 9.5F);
	/** Preset names in setup, dialog titles (SemiBold 7). */
	public static final FontFace TITLE = ScaledFont.of(FlintFonts.HEADING, 7.0F);
	/** Card names (SemiBold 6.6). */
	public static final FontFace CARD = ScaledFont.of(FlintFonts.STRONG, 6.6F);
	/** Row and layout names (SemiBold 6.4). */
	public static final FontFace NAME = ScaledFont.of(FlintFonts.STRONG, 6.4F);
	/** Main tabs (Medium 6.2). */
	public static final FontFace TAB = ScaledFont.of(FlintFonts.LABEL, 6.2F);
	/** Button labels (SemiBold 6). */
	public static final FontFace BUTTON = ScaledFont.of(FlintFonts.STRONG, 6.0F);
	/** Setting labels and options (Medium 6). */
	public static final FontFace LABEL = ScaledFont.of(FlintFonts.LABEL, 6.0F);
	/** Section tabs (Medium 5.6). */
	public static final FontFace SECTION = ScaledFont.of(FlintFonts.LABEL, 5.6F);
	/** Explanations (Regular 5.4). */
	public static final FontFace BODY = ScaledFont.of(FlintFonts.SMALL, 5.4F);
	/** Summaries, live values, values beside sliders (Medium 5.2). */
	public static final FontFace META = ScaledFont.of(FlintFonts.LABEL, 5.2F);
	/** Small notes and status lines (Regular 5). */
	public static final FontFace NOTE = ScaledFont.of(FlintFonts.SMALL, 5.0F);
	/** Status lines in emphasis (Medium 5). */
	public static final FontFace STATUS = ScaledFont.of(FlintFonts.LABEL, 5.0F);
	/** Section headers in capitals (SemiBold 4.6, tracked {@link #OVERLINE_TRACKING}). */
	public static final FontFace OVERLINE = ScaledFont.of(FlintFonts.STRONG, 4.6F);
	public static final float OVERLINE_TRACKING = 0.10F;
	/** "FLINT" beside the mark (Outfit Bold 6.6, tracked {@link #WORDMARK_TRACKING}). */
	public static final FontFace WORDMARK = ScaledFont.of(FlintFonts.WORDMARK, 6.6F);
	public static final float WORDMARK_TRACKING = 0.18F;

	/** A single line's height for a face: about 1.35 × its size. */
	public static float lineHeight(FontFace face) {
		return face.size() * 1.35F;
	}
}
