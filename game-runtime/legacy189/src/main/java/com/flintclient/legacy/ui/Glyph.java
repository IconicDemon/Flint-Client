package com.flintclient.legacy.ui;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import org.apache.logging.log4j.LogManager;

/**
 * The menu's icons, as Flint's 26.x menu draws them: Font Awesome 6 Free Solid glyphs (SIL OFL 1.1, bundled with its licence
 * in META-INF/licenses). Each is rasterised by Java's own font engine at the exact pixel size it is shown at, white with its
 * coverage in alpha, into the {@link Atlas}, and centred on its drawn pixels. Render thread (the font file is read once on a
 * worker at start-up).
 */
public enum Glyph {
	GRID(0xF009),
	GEAR(0xF013),
	SLIDERS(0xF1DE),
	ARROWS(0xF0B2),
	CLOSE(0xF00D),
	HEART(0xF004),
	SEARCH(0xF002),
	BORDER_ALL(0xF84C),
	CHEVRON_LEFT(0xF053),
	CHEVRON_RIGHT(0xF054),
	CHEVRON_DOWN(0xF078),
	CHEVRON_UP(0xF077),
	MOON(0xF186),
	SUN(0xF185),
	INFO(0xF05A),
	RESET(0xF2EA),
	CHECK(0xF00C),
	KEYBOARD(0xF11C),
	PEN(0xF303),
	PLUS(0xF067),
	MINUS(0xF068),
	EYE(0xF06E),
	LAYERS(0xF5FD),
	PIN(0xF3C5),
	TRASH(0xF1F8),
	EYE_SLASH(0xF070),
	CROSSHAIRS(0xF05B),
	SKULL(0xF54C);

	private static volatile Font font;
	private static volatile boolean loading;
	private final int codepoint;

	Glyph(int codepoint) {
		this.codepoint = codepoint;
	}

	/** Reads the font file on a worker, so the first menu frame doesn't wait for it. */
	public static void preload() {
		if (loading) {
			return;
		}
		loading = true;
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				font();
			}
		}, "Flint glyph font");
		t.setDaemon(true);
		t.start();
	}

	private static Font font() {
		Font f = font;
		if (f == null) {
			synchronized (Glyph.class) {
				f = font;
				if (f == null) {
					try (InputStream in = Glyph.class.getResourceAsStream("/assets/flintclient/font/fa-solid-900.ttf")) {
						f = Font.createFont(Font.TRUETYPE_FONT, in);
					} catch (Exception e) {
						LogManager.getLogger("Flint").error("Flint couldn't read its icon font", e);
						f = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
					}
					font = f;
				}
			}
		}
		return f;
	}

	/** This glyph about {@code size} window pixels tall, centred on ({@code centerX}, {@code centerY}). */
	public void draw(float centerX, float centerY, float size, int argb) {
		int px = Math.max(4, Math.round(size));
		long key = Atlas.key(Atlas.KIND_GLYPH, codepoint, px, 0);
		Atlas.Entry e = Atlas.get().find(key);
		if (e == null) {
			e = rasterise(key, px);
		}
		Gfx.entry(e, Math.round(centerX) + e.offsetX, Math.round(centerY) + e.offsetY, argb);
	}

	private Atlas.Entry rasterise(long key, int px) {
		Font f = font().deriveFont((float) px);
		FontRenderContext frc = new FontRenderContext(null, true, true);
		GlyphVector gv = f.createGlyphVector(frc, new String(Character.toChars(codepoint)));
		Rectangle bounds = gv.getPixelBounds(frc, 0, 0);
		int w = Math.max(1, bounds.width + 2);
		int h = Math.max(1, bounds.height + 2);
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setColor(java.awt.Color.WHITE);
		g.drawGlyphVector(gv, 1 - bounds.x, 1 - bounds.y);
		g.dispose();
		int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
		for (int i = 0; i < argb.length; i++) {
			argb[i] = (argb[i] & 0xFF000000) | 0xFFFFFF;
		}
		Atlas.Entry e = Atlas.get().add(key, w, h, argb);
		e.offsetX = -w / 2;
		e.offsetY = -h / 2;
		return e;
	}
}
