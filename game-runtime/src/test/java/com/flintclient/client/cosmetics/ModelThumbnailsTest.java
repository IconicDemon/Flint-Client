package com.flintclient.client.cosmetics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelThumbnailsTest {
	@Test
	void everyAngleShowsTheModelAtOneScale() throws Exception {
		CosmeticModel hat = CosmeticModel.bake(CosmeticGeometry.parse(Placeholders.bytes(Placeholders.HEADWEAR)), CosmeticModel.HEAD, false);
		int[] tex = new int[64 * 32];
		java.util.Arrays.fill(tex, 0xFFFF8A1F);
		int size = 48;
		int[] atlas = ModelThumbnails.render(hat, new ModelPose(hat, null), tex, 64, 32, 1, size);
		int atlasSize = ModelThumbnails.atlasSize(size);
		assertEquals(atlasSize * atlasSize, atlas.length);
		for (int a = 0; a < ModelThumbnails.ANGLES; a++) {
			int filled = 0;
			int ox = (a % ModelThumbnails.COLUMNS) * size;
			int oy = (a / ModelThumbnails.COLUMNS) * size;
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					if ((atlas[(oy + y) * atlasSize + ox + x] >>> 24) != 0) {
						filled++;
					}
				}
			}
			// A round hat looks the same size from every side: a fair part of the cell, and never past it.
			assertTrue(filled > size * size / 8 && filled < size * size, "angle " + a + ": " + filled);
		}
		assertEquals(0, ModelThumbnails.cell(0));
		assertEquals(4, ModelThumbnails.cell(90));
		assertEquals(0, ModelThumbnails.cell(-1));
		assertEquals(12, ModelThumbnails.cell(-90));
	}
}
