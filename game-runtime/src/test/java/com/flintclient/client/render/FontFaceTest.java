package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FontFaceTest {
	@Test
	void theRolesKeepTheirSizesAsFaces() {
		// The main menu's text depends on these: FontFace must not change them.
		FontFace label = FlintFonts.LABEL;
		assertEquals(4.6F, label.size());
		assertEquals(FlintFonts.LABEL.size, label.size());
		assertEquals(7.0F, FlintFonts.HEADING.size());
		assertEquals(2.9F, FlintFonts.CAPS.size());
		assertEquals(FlintFonts.SMALL.size * FlintFonts.SMALL.capHeight * 3, FlintFonts.SMALL.capHeightPx(3), 1e-5);
	}
}
