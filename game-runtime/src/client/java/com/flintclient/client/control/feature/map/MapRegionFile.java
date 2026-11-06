package com.flintclient.client.control.feature.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;

/**
 * One region's file ({@code r.<x>.<z>.fmap}): a small header and the known-chunk bits, then the 64 × 64 preview, then the
 * full columns, each part compressed on its own so a zoomed-out view reads only the preview. Written to a temporary file and
 * moved into place, so a crash mid-write never leaves a half file. Version-neutral; the file thread.
 */
public final class MapRegionFile {
	static final int MAGIC = 0x464D4150;
	static final int VERSION = 1;

	private MapRegionFile() {
	}

	public static String name(int rx, int rz) {
		return "r." + rx + "." + rz + ".fmap";
	}

	/** The region a file name stands for, as {@link MapRegion#key}, or null for another file. */
	public static @Nullable Long parse(String fileName) {
		if (!fileName.startsWith("r.") || !fileName.endsWith(".fmap")) {
			return null;
		}
		String[] parts = fileName.substring(2, fileName.length() - 5).split("\\.");
		if (parts.length != 2) {
			return null;
		}
		try {
			return MapRegion.key(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static void write(Path file, MapRegion.Snapshot s) throws IOException {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.createDirectories(file.getParent());
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), 1 << 16))) {
			write(out, s);
		}
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	static void write(DataOutputStream out, MapRegion.Snapshot s) throws IOException {
		out.writeInt(MAGIC);
		out.writeInt(VERSION);
		out.writeInt(s.x());
		out.writeInt(s.z());
		for (long bits : s.known()) {
			out.writeLong(bits);
		}
		ByteBuffer preview = ByteBuffer.allocate(s.previewColor().length * 6).order(ByteOrder.BIG_ENDIAN);
		preview.asIntBuffer().put(s.previewColor());
		preview.position(s.previewColor().length * 4);
		preview.asShortBuffer().put(s.previewHeight());
		block(out, preview.array());
		if (s.color() == null) {
			out.writeInt(0);
			return;
		}
		ByteBuffer full = ByteBuffer.allocate(MapRegion.AREA * 7).order(ByteOrder.BIG_ENDIAN);
		full.asIntBuffer().put(s.color());
		full.position(MapRegion.AREA * 4);
		full.asShortBuffer().put(s.height());
		full.position(MapRegion.AREA * 6);
		full.put(s.biome());
		block(out, full.array());
	}

	private static void block(DataOutputStream out, byte[] raw) throws IOException {
		Deflater deflater = new Deflater(3);
		try {
			deflater.setInput(raw);
			deflater.finish();
			ByteArrayOutputStream packed = new ByteArrayOutputStream(raw.length / 4);
			byte[] buffer = new byte[1 << 16];
			while (!deflater.finished()) {
				int n = deflater.deflate(buffer);
				packed.write(buffer, 0, n);
			}
			out.writeInt(raw.length);
			out.writeInt(packed.size());
			packed.writeTo(out);
		} finally {
			deflater.end();
		}
	}

	/** Reads a region file: the preview only, or everything; null when it isn't a region file of this version. */
	public static MapRegion.@Nullable Snapshot read(Path file, boolean full) throws IOException {
		try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16))) {
			return read(in, full);
		}
	}

	static MapRegion.@Nullable Snapshot read(DataInputStream in, boolean full) throws IOException {
		if (in.readInt() != MAGIC || in.readInt() != VERSION) {
			return null;
		}
		int x = in.readInt();
		int z = in.readInt();
		long[] known = new long[MapRegion.CHUNKS * MapRegion.CHUNKS / 64];
		for (int i = 0; i < known.length; i++) {
			known[i] = in.readLong();
		}
		int previewArea = MapRegion.PREVIEW * MapRegion.PREVIEW;
		byte[] preview = unblock(in, previewArea * 6);
		if (preview == null) {
			return null;
		}
		ByteBuffer p = ByteBuffer.wrap(preview).order(ByteOrder.BIG_ENDIAN);
		int[] previewColor = new int[previewArea];
		short[] previewHeight = new short[previewArea];
		p.asIntBuffer().get(previewColor);
		p.position(previewArea * 4);
		p.asShortBuffer().get(previewHeight);
		if (!full) {
			return new MapRegion.Snapshot(x, z, known, previewColor, previewHeight, null, null, null);
		}
		byte[] rest = unblock(in, MapRegion.AREA * 7);
		if (rest == null) {
			return new MapRegion.Snapshot(x, z, known, previewColor, previewHeight, null, null, null);
		}
		ByteBuffer f = ByteBuffer.wrap(rest).order(ByteOrder.BIG_ENDIAN);
		int[] color = new int[MapRegion.AREA];
		short[] height = new short[MapRegion.AREA];
		byte[] biome = new byte[MapRegion.AREA];
		f.asIntBuffer().get(color);
		f.position(MapRegion.AREA * 4);
		f.asShortBuffer().get(height);
		f.position(MapRegion.AREA * 6);
		f.get(biome);
		return new MapRegion.Snapshot(x, z, known, previewColor, previewHeight, color, height, biome);
	}

	private static byte @Nullable [] unblock(DataInputStream in, int expected) throws IOException {
		int rawLength = in.readInt();
		if (rawLength == 0) {
			return null;
		}
		int packedLength = in.readInt();
		if (rawLength != expected || packedLength <= 0 || packedLength > expected + 1024) {
			throw new IOException("bad map block " + rawLength + "/" + packedLength);
		}
		byte[] packed = in.readNBytes(packedLength);
		Inflater inflater = new Inflater();
		try {
			inflater.setInput(packed);
			byte[] out = new byte[rawLength];
			int n = 0;
			while (n < rawLength && !inflater.finished()) {
				int got = inflater.inflate(out, n, rawLength - n);
				if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					break;
				}
				n += got;
			}
			if (n != rawLength) {
				throw new IOException("short map block");
			}
			return out;
		} catch (DataFormatException e) {
			throw new IOException(e);
		} finally {
			inflater.end();
		}
	}

	/** For tests: the bytes a snapshot writes. */
	static byte[] bytes(MapRegion.Snapshot s) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (DataOutputStream data = new DataOutputStream(out)) {
			write(data, s);
		}
		return out.toByteArray();
	}

	static void copy(InputStream in, OutputStream out) throws IOException {
		in.transferTo(out);
	}
}
