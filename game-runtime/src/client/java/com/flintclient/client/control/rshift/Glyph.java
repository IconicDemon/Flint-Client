package com.flintclient.client.control.rshift;

import com.flintclient.client.render.Canvas;

/**
 * The Right Shift menu's icons, as Dawn draws them: Font Awesome 6 Free Solid glyphs (SIL OFL 1.1, bundled with its
 * licence), each one em tall, rasterised at the exact pixel size they are drawn at (see {@code GlyphRaster}). Render thread.
 */
enum Glyph {
	GRID(0xF009),
	GEAR(0xF013),
	GAUGE(0xF625),
	GEM(0xF3A5),
	EYE(0xF06E),
	USERS(0xF0C0),
	LAYERS(0xF5FD),
	PIN(0xF3C5),
	PALETTE(0xF53F),
	CLOSE(0xF00D),
	HEART(0xF004),
	SEARCH(0xF002),
	BORDER_ALL(0xF84C),
	LIST(0xF03A),
	CHEVRON_LEFT(0xF053),
	CHEVRON_RIGHT(0xF054),
	CHEVRON_DOWN(0xF078),
	CHEVRON_UP(0xF077),
	MOON(0xF186),
	SUN(0xF185),
	INFO(0xF05A),
	RESET(0xF2EA),
	CHECK(0xF00C),
	PLUS(0xF067),
	TRASH(0xF1F8),
	PEN(0xF303),
	KEYBOARD(0xF11C),
	DROPPER(0xF1FB),
	COPY(0xF0C5),
	MOVE(0xF047),
	CAMERA(0xF030),
	FLAG(0xF024),
	FOLDER(0xF07B),
	MINUS(0xF068),
	PLAY(0xF04B),
	SHARE(0xF14D),
	DOWNLOAD(0xF019),
	HOURGLASS(0xF252),
	DISPLAY(0xE163),
	GIFT(0xF06B),
	FIRE(0xF06D),
	CROWN(0xF521),
	CLOCK(0xF017),
	TROPHY(0xF091);

	private final int codepoint;

	Glyph(int codepoint) {
		this.codepoint = codepoint;
	}

	/** This glyph {@code size} layout units tall, centred on ({@code centerX}, {@code centerY}). */
	void draw(Canvas c, float centerX, float centerY, float size, int argb) {
		int px = Math.max(1, c.px(size));
		c.glyph(codepoint, c.px(centerX) - px / 2, c.px(centerY) - px / 2, px, argb);
	}
}
