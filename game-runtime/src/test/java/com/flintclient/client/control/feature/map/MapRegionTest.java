package com.flintclient.client.control.feature.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapRegionTest {
	private static int[] colors(int rgb, int kind) {
		int[] c = new int[256];
		Arrays.fill(c, MapShading.pack(kind, rgb));
		return c;
	}

	private static short[] heights(int h) {
		short[] out = new short[256];
		Arrays.fill(out, (short) h);
		return out;
	}

	@Test
	void aRescanThatFindsTheSameChangesNothing() {
		MapRegion r = new MapRegion(0, 0, true);
		assertTrue(r.empty());
		assertTrue(r.writeChunk(3, 4, colors(0x336699, MapRegion.LAND), heights(70), new byte[256]));
		int revision = r.revision();
		assertTrue(r.known(3, 4));
		assertFalse(r.known(4, 4));
		assertEquals(revision, r.chunkRevision(3, 4));
		assertFalse(r.writeChunk(3, 4, colors(0x336699, MapRegion.LAND), heights(70), new byte[256]), "same data: no change");
		assertEquals(revision, r.revision());
		assertTrue(r.writeChunk(3, 4, colors(0x336699, MapRegion.LAND), heights(71), new byte[256]));
		assertTrue(r.revision() > revision);
		assertEquals(71, r.height(3 * 16 + 5, 4 * 16 + 9));
		assertEquals(MapRegion.LAND, r.color(3 * 16, 4 * 16) >>> 24);
		assertEquals(0, r.color(0, 0), "never seen: unknown");
	}

	@Test
	void thePreviewAveragesEightByEightBlocks() {
		MapRegion r = new MapRegion(0, 0, true);
		r.writeChunk(0, 0, colors(0x204060, MapRegion.WATER), heights(62), new byte[256]);
		r.updatePreview();
		assertEquals(MapShading.pack(MapRegion.WATER, 0x204060), r.previewColor(0, 0));
		assertEquals(MapShading.pack(MapRegion.WATER, 0x204060), r.previewColor(1, 1));
		assertEquals(62, r.previewHeight(1, 0));
		assertEquals(0, r.previewColor(2, 0), "the next chunk is unknown");
	}

	@Test
	void filesRoundTripWholeOrPreviewOnly() throws Exception {
		MapRegion r = new MapRegion(-3, 7, true);
		r.writeChunk(31, 0, colors(0xABCDEF, MapRegion.LAND), heights(-20), new byte[256]);
		byte[] biomes = new byte[256];
		Arrays.fill(biomes, (byte) 5);
		r.writeChunk(0, 31, colors(0x123456, MapRegion.LAND), heights(300), biomes);
		assertTrue(r.unsaved());
		MapRegion.Snapshot s = r.snapshot();
		assertFalse(r.unsaved());
		byte[] bytes = MapRegionFile.bytes(s);
		MapRegion.Snapshot full = MapRegionFile.read(new DataInputStream(new ByteArrayInputStream(bytes)), true);
		assertNotNull(full);
		assertEquals(-3, full.x());
		assertEquals(7, full.z());
		assertArrayEquals(s.color(), full.color());
		assertArrayEquals(s.height(), full.height());
		assertArrayEquals(s.biome(), full.biome());
		assertArrayEquals(s.known(), full.known());
		MapRegion.Snapshot preview = MapRegionFile.read(new DataInputStream(new ByteArrayInputStream(bytes)), false);
		assertNotNull(preview);
		assertNull(preview.color());
		assertArrayEquals(s.previewColor(), preview.previewColor());
	}

	@Test
	void whatWasSeenThisSessionWinsOverTheFile() {
		MapRegion old = new MapRegion(0, 0, true);
		old.writeChunk(1, 1, colors(0x111111, MapRegion.LAND), heights(10), new byte[256]);
		old.writeChunk(2, 2, colors(0x222222, MapRegion.LAND), heights(20), new byte[256]);
		MapRegion.Snapshot file = old.snapshot();
		MapRegion now = new MapRegion(0, 0, true);
		now.writeChunk(1, 1, colors(0x999999, MapRegion.LAND), heights(99), new byte[256]);
		now.merge(file);
		assertEquals(0x999999, now.color(16, 16) & 0xFFFFFF, "seen this session");
		assertEquals(0x222222, now.color(32, 32) & 0xFFFFFF, "from the file");
		assertTrue(now.known(2, 2));
		assertTrue(now.unsaved(), "this session added something");
	}

	@Test
	void fileNamesParseBack() {
		assertEquals("r.-1.20.fmap", MapRegionFile.name(-1, 20));
		Long key = MapRegionFile.parse("r.-1.20.fmap");
		assertNotNull(key);
		assertEquals(-1, MapRegion.keyX(key));
		assertEquals(20, MapRegion.keyZ(key));
		assertNull(MapRegionFile.parse("r.1.fmap"));
		assertNull(MapRegionFile.parse("biomes.txt"));
	}

	@Test
	void aWorldWritesQuietRegionsAndReadsThemBack(@TempDir Path dir) throws Exception {
		MapWorld world = new MapWorld(dir);
		MapWorld.settle();
		world.drain();
		assertTrue(world.listed());
		MapRegion r = world.forWrite(2, -1);
		r.writeChunk(5, 5, colors(0x445566, MapRegion.LAND), heights(64), new byte[256]);
		world.changed(r);
		assertEquals(1, world.biome("minecraft:plains"));
		assertEquals(1, world.biome("minecraft:plains"), "kept once");
		assertEquals("minecraft:plains", world.biomeName(1));
		world.close();
		MapWorld.settle();
		assertTrue(Files.isRegularFile(dir.resolve("r.2.-1.fmap")));
		assertTrue(Files.isRegularFile(dir.resolve("biomes.txt")));
		MapWorld again = new MapWorld(dir);
		MapWorld.settle();
		again.drain();
		assertTrue(again.explored().contains(MapRegion.key(2, -1)));
		assertNull(again.request(2, -1, true), "read in the background first");
		MapWorld.settle();
		again.drain();
		MapRegion read = again.request(2, -1, true);
		assertNotNull(read);
		assertEquals(0x445566, read.color(5 * 16 + 1, 5 * 16 + 1) & 0xFFFFFF);
		assertEquals("minecraft:plains", again.biomeName(1));
		again.close();
	}
}
