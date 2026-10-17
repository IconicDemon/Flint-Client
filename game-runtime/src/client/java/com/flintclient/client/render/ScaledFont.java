package com.flintclient.client.render;

import com.flintclient.client.FlintClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

/**
 * One of Flint's bundled typefaces at a size of the caller's choosing, for text that scales in steps
 * (HUD modules at 0.75×, 1.25×, ...). Like the {@link FlintFonts} roles it is rasterised once per pixel
 * scale, so it stays crisp: the font {@code flintclient:scaled_<file>_<size×100>_<scale>} is registered
 * with the same names {@code FontManagerMixin} resolves, and its font set is only created when it is
 * first drawn.
 *
 * <p>Every font set costs a glyph table and texture, so sizes are quantised to {@value #STEPS_PER_UNIT}
 * steps per unit, and once {@value #MAX_FACES} faces exist the nearest existing size of the same file is
 * reused instead of adding one (only a file with no face yet still gets its first). Callers keep the face
 * they get (looking one up is for set-up, not every frame). Render thread.
 */
public final class ScaledFont implements FontFace {
	/** The most distinct (file, size) faces; the HUD's few files at six scale steps stay far below it. */
	public static final int MAX_FACES = 64;
	/** Sizes are rounded to 1/20 of a unit. */
	public static final int STEPS_PER_UNIT = 20;
	private static final float MIN_SIZE = 0.5F;
	private static final float MAX_SIZE = 64.0F;
	private static final List<ScaledFont> FACES = new ArrayList<>();
	private static boolean warnedFull;

	private final String file;
	private final String stem;
	/** The size in hundredths of a unit (its name and its identity). */
	private final int hundredths;
	private final float size;
	private final float capHeight;
	private final FlintFonts.Coverage coverage;
	private final FontDescription.Resource[] perScale = new FontDescription.Resource[FlintFonts.MAX_SCALE + 1];

	private ScaledFont(String file, int hundredths, float capHeight, FlintFonts.Coverage coverage) {
		this.file = file;
		this.stem = file.endsWith(".ttf") ? file.substring(0, file.length() - 4) : file;
		this.hundredths = hundredths;
		this.size = hundredths / 100.0F;
		this.capHeight = capHeight;
		this.coverage = coverage;
	}

	/** {@code like}'s typeface (file, cap height, character coverage) at {@code size} units. */
	public static ScaledFont of(FlintFonts like, float size) {
		int hundredths = quantise(size);
		ScaledFont nearest = null;
		for (int i = 0; i < FACES.size(); i++) {
			ScaledFont face = FACES.get(i);
			if (face.file.equals(like.file())) {
				if (face.hundredths == hundredths) {
					return face;
				}
				if (nearest == null || Math.abs(face.hundredths - hundredths) < Math.abs(nearest.hundredths - hundredths)) {
					nearest = face;
				}
			}
		}
		if (FACES.size() >= MAX_FACES && nearest != null) {
			if (!warnedFull) {
				warnedFull = true;
				FlintClient.LOGGER.warn("Flint has {} scaled font sizes already; reusing the nearest size from now on", MAX_FACES);
			}
			return nearest;
		}
		ScaledFont face = new ScaledFont(like.file(), hundredths, like.capHeight, like.coverage());
		FACES.add(face);
		return face;
	}

	/** {@code like} enlarged by {@code factor} (a HUD scale step). */
	public static ScaledFont scaled(FlintFonts like, float factor) {
		return of(like, like.size * factor);
	}

	/** A size in hundredths of a unit, rounded to the nearest {@link #STEPS_PER_UNIT step} and kept in range. */
	static int quantise(float size) {
		float clamped = Math.clamp(size, MIN_SIZE, MAX_SIZE);
		return Math.round(clamped * STEPS_PER_UNIT) * (100 / STEPS_PER_UNIT);
	}

	/** The font name for one pixel scale, as registered for FontManagerMixin. */
	static String name(String stem, int hundredths, int scale) {
		return "scaled_" + stem + "_" + hundredths + "_" + scale;
	}

	@Override
	public FontDescription forScale(int scale) {
		int s = Math.clamp(scale, 1, FlintFonts.MAX_SCALE);
		FontDescription.Resource resource = perScale[s];
		if (resource == null) {
			Identifier id = Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, name(stem, hundredths, s));
			FlintFonts.register(id, new FlintFonts.Spec(file, size, s));
			resource = new FontDescription.Resource(id);
			perScale[s] = resource;
		}
		return resource;
	}

	@Override
	public float size() {
		return size;
	}

	@Override
	public float capHeightPx(int scale) {
		return size * capHeight * scale;
	}

	@Override
	public boolean covers(String text) {
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			if (!coverage.covers(cp)) {
				return false;
			}
			i += Character.charCount(cp);
		}
		return true;
	}

	@Override
	public String toString() {
		return stem + "@" + size;
	}
}
