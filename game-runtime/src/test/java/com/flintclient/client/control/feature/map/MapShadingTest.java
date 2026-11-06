package com.flintclient.client.control.feature.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MapShadingTest {
	private static int brightness(int argb) {
		return (argb >> 16 & 255) + (argb >> 8 & 255) + (argb & 255);
	}

	@Test
	void unknownStaysClearAndKnownIsOpaque() {
		assertEquals(0, MapShading.pixel(0, 64, 64, 64, MapShading.Look.DEFAULT));
		int land = MapShading.pixel(MapShading.pack(MapRegion.LAND, 0x808080), 64, 64, 64, MapShading.Look.DEFAULT);
		assertEquals(0xFF, land >>> 24);
	}

	@Test
	void slopesLightRisesAndShadeDrops() {
		MapShading.Look look = new MapShading.Look(MapShading.SLOPES, false, false, 63);
		int flat = MapShading.pixel(MapShading.pack(MapRegion.LAND, 0x808080), 70, 70, 70, look);
		int rise = MapShading.pixel(MapShading.pack(MapRegion.LAND, 0x808080), 72, 70, 70, look);
		int drop = MapShading.pixel(MapShading.pack(MapRegion.LAND, 0x808080), 68, 70, 70, look);
		assertTrue(brightness(rise) > brightness(flat));
		assertTrue(brightness(drop) < brightness(flat));
		int water = MapShading.pixel(MapShading.pack(MapRegion.WATER, 0x3050A0), 62, 50, 50, look);
		assertEquals(0xFF3050A0, water, "water is flat");
	}

	@Test
	void threeDLightsSlopesFacingTheNorthWestSun() {
		MapShading.Look look = new MapShading.Look(MapShading.RELIEF, false, false, 63, MapShading.OVERWORLD_SHADOW);
		int land = MapShading.pack(MapRegion.LAND, 0x808080);
		int flat = MapShading.pixel(land, 70, 70, 70, look);
		int lit = MapShading.pixel(land, 71, 70, 70, look);
		int shaded = MapShading.pixel(land, 66, 70, 70, look);
		assertTrue(brightness(lit) > brightness(flat));
		assertTrue(brightness(shaded) < brightness(flat));
		assertTrue((flat & 255) > (flat >> 16 & 255), "the Overworld's ambient light is sky blue");
	}

	@Test
	void heightTintAndHeightColours() {
		MapShading.Look tint = new MapShading.Look(MapShading.FLAT, true, false, 63);
		int low = MapShading.pixel(MapShading.pack(MapRegion.LAND, 0x808080), 20, 20, 20, tint);
		int high = MapShading.pixel(MapShading.pack(MapRegion.LAND, 0x808080), 150, 150, 150, tint);
		assertTrue(brightness(high) > brightness(low));
		assertTrue(MapShading.heightColor(-40, 63) != MapShading.heightColor(200, 63));
		assertEquals(0xFFFFFF, MapShading.heightColor(400, 63));
	}

	/** Water over sand as the scanner reads it: one layer of water, its floor lit by the sunlight left after {@code depth} blocks. */
	private static int waterOver(int water, int floor, int depth) {
		float[] acc = {0, 0, 0, 1};
		MapShading.overlay(acc, water, 0.7F, 15);
		return MapShading.under(acc, floor, MapShading.floorLight(15 - depth));
	}

	@Test
	void deepWaterIsDarkerThanShallows() {
		int sand = 0xD8C890;
		int water = 0x2040C0;
		int shallow = waterOver(water, sand, 1);
		int deep = waterOver(water, sand, 20);
		assertTrue(brightness(deep) < brightness(shallow), "deeper is darker");
		assertTrue((deep & 255) > (deep >> 16 & 255), "deep water is blue");
		// Past 15 blocks no sunlight is left: the floor shows at 9/24 under the water.
		assertEquals(waterOver(water, sand, 15), waterOver(water, sand, 40));
		assertEquals(0.375F, MapShading.floorLight(0), 1e-6);
		assertEquals(1F, MapShading.floorLight(15), 1e-6);
	}

	@Test
	void glowingBlocksAreBrightAndKeepTheirColourOnSlopes() {
		int lava = MapShading.glow(0x806040);
		assertTrue(brightness(lava) >= 405, "brightened to Xaero's 407");
		MapShading.Look look = new MapShading.Look(MapShading.RELIEF, true, false, 63, MapShading.OVERWORLD_SHADOW);
		int flat = MapShading.pixel(MapShading.pack(MapRegion.GLOW, lava), 10, 10, 10, 10, look);
		int shaded = MapShading.pixel(MapShading.pack(MapRegion.GLOW, lava), 6, 10, 10, 10, look);
		assertTrue(brightness(shaded) >= brightness(lava) - 3, "a glowing block never darkens");
		assertTrue(brightness(flat) >= brightness(shaded));
	}

	@Test
	void theVoidIsFlatViolet() {
		int v = MapShading.pixel(MapShading.pack(MapRegion.VOID, MapShading.VOID_RGB), -64, 60, 60, 60, MapShading.Look.DEFAULT);
		assertEquals(0xFF000000 | MapShading.VOID_RGB, v);
	}

	@Test
	void caveLayersRunTheirTerrainDepthDownTheLayer() {
		MapShading.Look look = new MapShading.Look(MapShading.FLAT, true, false, 63).cave(40, 30);
		int land = MapShading.pack(MapRegion.LAND, 0x808080);
		int bottom = MapShading.pixel(land, 10, 10, 10, 10, look);
		int top = MapShading.pixel(land, 40, 40, 40, 40, look);
		assertTrue(brightness(top) > brightness(bottom));
		assertEquals(look, look.cave(40, 30), "the same layer is the same look");
	}

	@Test
	void threeDReadsTheRealNorthWestNeighbour() {
		MapShading.Look look = new MapShading.Look(MapShading.RELIEF, false, false, 63, MapShading.OTHER_SHADOW);
		int land = MapShading.pack(MapRegion.LAND, 0x808080);
		// Level to the north and west, but a step down to the north-west: lit.
		int lit = MapShading.pixel(land, 70, 70, 70, 69, look);
		int flat = MapShading.pixel(land, 70, 70, 70, 70, look);
		assertTrue(brightness(lit) != brightness(flat));
	}
}
