package com.flintclient.client.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Pictures from the Flint app are checked by their header before anything is decoded. */
class PictureHeaderTest {
	private static byte[] encode(int w, int h, String format) throws IOException {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setRGB(x, y, x < w / 2 ? 0xD97706 : 0x0F1014);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

	@Test
	void aPngsSizeIsReadFromItsHeader() throws IOException {
		assertArrayEquals(new int[] {32, 18}, PictureHeader.pngSize(encode(32, 18, "png")));
		byte[] huge = encode(8, 8, "png");
		// IHDR says 5000 wide: refused before decoding.
		huge[16] = 0;
		huge[17] = 0;
		huge[18] = 0x13;
		huge[19] = (byte) 0x88;
		assertNull(PictureHeader.pngSize(huge));
		assertNull(PictureHeader.pngSize(encode(8, 8, "jpeg")));
	}

	@Test
	void aJpegIsDecodedToArgb() throws IOException {
		Pixels pixels = PictureHeader.readJpeg(encode(40, 20, "jpeg"));
		assertEquals(40, pixels.width());
		assertEquals(20, pixels.height());
		int left = pixels.get(5, 10);
		assertEquals(0xFF, left >>> 24);
		// JPEG is lossy: the orange half stays orange.
		assertEquals(true, ((left >> 16) & 0xFF) > 180 && (left & 0xFF) < 60);
		assertNull(PictureHeader.readJpeg(encode(8, 8, "png")));
	}
}
