package com.flintclient.client.control.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An animated cape's timing manifest, as the Store gives it beside the cape's poster ({@code asset.animation}; Store
 * docs/LAUNCHER-API.md "Animated capes", src/lib/cape-animation.ts {@code isCapeAnimation}): the 22×17 cape atlas (the cape
 * region of Minecraft's 64×32 cape texture) at {@link #scale} pixels per unit, {@link #frameCount} frames each shown for its own
 * duration in milliseconds, stacked top to bottom in PNG strips at most {@value #MAX_STRIP_HEIGHT} px tall.
 *
 * <p>Untrusted: {@link #parse} answers null for anything that isn't exactly a version-1 manifest within the Store's limits (2–120
 * frames of 10–10000 ms, at most 60 s a loop, strips of at most 8 MiB and 32 MiB together, each at an address
 * {@link StoreEndpoint#allowsAsset} allows, with a SHA-256): such a cape shows its poster as a static cape, as a game that
 * doesn't read animations would.
 *
 * <p>Timing is the wall clock's ({@link #frameAt}): frame {@code f} shows for its duration and the loop repeats forever, starting
 * at {@code now % loop}, so every Flint player sees the same frame at the same moment. Immutable; pure Java; version-neutral.
 */
public final class CapeAnimation {
	public static final int ATLAS_WIDTH = 22;
	public static final int ATLAS_HEIGHT = 17;
	/** Pixels per atlas unit the Store makes animations at: 88×68, 176×136, 352×272 (standard) and 704×544 (HD) frames. */
	static final int[] SCALES = {4, 8, 16, 32};
	/** The scales a card preview is made at (Store PREVIEW_SCALES), always below its animation's. */
	static final int[] PREVIEW_SCALES = {4, 8, 16};
	public static final int MIN_FRAMES = 2;
	public static final int MAX_FRAMES = 120;
	public static final int MIN_FRAME_MILLIS = 10;
	public static final int MAX_FRAME_MILLIS = 10_000;
	public static final int MAX_LOOP_MILLIS = 60_000;
	public static final int MAX_STRIP_HEIGHT = 2048;
	/** The widest strip: an HD frame. */
	public static final int MAX_STRIP_WIDTH = ATLAS_WIDTH * 32;
	public static final int MAX_STRIP_BYTES = 8 * 1024 * 1024;
	public static final int MAX_TOTAL_STRIP_BYTES = 32 * 1024 * 1024;
	public static final String LAYOUT = "cape-atlas-22x17";
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	/**
	 * One PNG of frames stacked top to bottom: download {@code cdnUrl} when given (Flint's CDN, the same file), else or when that
	 * fails {@code url}; check its {@code bytes} and {@code sha256}.
	 */
	public record Strip(URI url, String sha256, int bytes, int frames, @Nullable URI cdnUrl) {
		public Strip(URI url, String sha256, int bytes, int frames) {
			this(url, sha256, bytes, frames, null);
		}
	}

	private final int scale;
	private final int[] durations;
	/** {@code ends[f]}: when frame {@code f} ends, in milliseconds from the loop's start (exclusive); the last is the loop. */
	private final int[] ends;
	private final int framesPerStrip;
	private final List<Strip> strips;
	private final String key;
	/** The card preview (Store {@code animation.preview}): the same frames and timing at a smaller scale; null when none. */
	private final @Nullable CapeAnimation preview;

	private CapeAnimation(int scale, int[] durations, int framesPerStrip, List<Strip> strips, @Nullable CapeAnimation preview) {
		this.scale = scale;
		this.durations = durations;
		this.ends = new int[durations.length];
		int sum = 0;
		for (int f = 0; f < durations.length; f++) {
			sum += durations[f];
			ends[f] = sum;
		}
		this.framesPerStrip = framesPerStrip;
		this.strips = List.copyOf(strips);
		this.key = key(scale, durations, this.strips);
		this.preview = preview;
	}

	/**
	 * The Store's card preview of this animation ({@code animation.preview}, Store src/lib/cape-animation.ts {@code CapePreview}):
	 * every frame, each for its own duration, at a smaller scale (4, 8 or 16, below this one's), its own strips; an animation of
	 * its own (its {@link #preview} is null). Null when the Store gave none, or one that doesn't check out (the full strips are
	 * then used). Other players' capes play it: about a quarter of the bytes for what a cape in the world shows.
	 */
	public @Nullable CapeAnimation preview() {
		return preview;
	}

	/** What another player's cape plays: the {@link #preview} when there is one, else this. */
	public CapeAnimation forOthers() {
		return preview != null ? preview : this;
	}

	/** Pixels per atlas unit: 4, 8, 16 or 32. */
	public int scale() {
		return scale;
	}

	public int frameWidth() {
		return ATLAS_WIDTH * scale;
	}

	public int frameHeight() {
		return ATLAS_HEIGHT * scale;
	}

	public int frameCount() {
		return durations.length;
	}

	/** How long frame {@code f} shows, in milliseconds. */
	public int duration(int f) {
		return durations[f];
	}

	/** One loop, in milliseconds. */
	public int loopMillis() {
		return ends[ends.length - 1];
	}

	public int framesPerStrip() {
		return framesPerStrip;
	}

	public List<Strip> strips() {
		return strips;
	}

	/** The first frame strip {@code s} holds. */
	public int firstFrame(int s) {
		return s * framesPerStrip;
	}

	/** Every strip's bytes together, as the Store gave them. */
	public long stripBytes() {
		long total = 0;
		for (Strip s : strips) {
			total += s.bytes();
		}
		return total;
	}

	/**
	 * A short name for this animation, the same for the same frames and timing (16 hex characters of a SHA-256 over the scale,
	 * the durations and every strip's hash): textures and caches tell animations apart by it.
	 */
	public String key() {
		return key;
	}

	/**
	 * The frame shown at {@code epochMillis} (the wall clock, {@link System#currentTimeMillis}): the loop starts at
	 * {@code epochMillis % loop}, so every client shows the same frame. A binary search over the frames' end times.
	 */
	public int frameAt(long epochMillis) {
		int t = (int) Math.floorMod(epochMillis, (long) loopMillis());
		int lo = 0;
		int hi = ends.length - 1;
		while (lo < hi) {
			int mid = (lo + hi) >>> 1;
			if (ends[mid] > t) {
				hi = mid;
			} else {
				lo = mid + 1;
			}
		}
		return lo;
	}

	/** How many frames one strip holds for frames {@code frameHeight} tall (the Store's {@code framesPerStrip}). */
	public static int framesPerStrip(int frameHeight) {
		return Math.max(1, MAX_STRIP_HEIGHT / frameHeight);
	}

	/**
	 * {@code json} as an animation whose strips come from where {@code endpoint} allows, or null when it is absent ({@code null}:
	 * a static cape) or isn't exactly a version-1 manifest within the Store's limits.
	 */
	public static @Nullable CapeAnimation parse(@Nullable JsonElement json, StoreEndpoint endpoint) {
		if (json == null || !json.isJsonObject()) {
			return null;
		}
		try {
			return read(json.getAsJsonObject(), endpoint);
		} catch (RuntimeException e) {
			// Gson's own surprises (a number too large, a deep structure) refuse the manifest like any other mistake.
			return null;
		}
	}

	private static @Nullable CapeAnimation read(JsonObject a, StoreEndpoint endpoint) {
		Long v = JsonFields.integer(a, "v");
		String layout = JsonFields.string(a, "layout");
		Long scale = JsonFields.integer(a, "scale");
		Long width = JsonFields.integer(a, "width");
		Long height = JsonFields.integer(a, "height");
		Long frameCount = JsonFields.integer(a, "frame_count");
		Long loop = JsonFields.integer(a, "duration_ms");
		Long perStrip = JsonFields.integer(a, "frames_per_strip");
		if (v == null || v != 1 || !LAYOUT.equals(layout) || scale == null || !Arrays.stream(SCALES).anyMatch(s -> s == scale)
			|| width == null || width != ATLAS_WIDTH * scale || height == null || height != ATLAS_HEIGHT * scale || frameCount == null
			|| frameCount < MIN_FRAMES || frameCount > MAX_FRAMES || loop == null || perStrip == null
			|| perStrip != framesPerStrip(height.intValue())) {
			return null;
		}
		JsonElement durationsJson = a.get("durations_ms");
		JsonElement stripsJson = a.get("strips");
		if (durationsJson == null || !durationsJson.isJsonArray() || stripsJson == null || !stripsJson.isJsonArray()) {
			return null;
		}
		JsonArray durationsArray = durationsJson.getAsJsonArray();
		int frames = frameCount.intValue();
		if (durationsArray.size() != frames) {
			return null;
		}
		int[] durations = new int[frames];
		long sum = 0;
		for (int f = 0; f < frames; f++) {
			Long d = integer(durationsArray.get(f));
			if (d == null || d < MIN_FRAME_MILLIS || d > MAX_FRAME_MILLIS) {
				return null;
			}
			durations[f] = d.intValue();
			sum += d;
		}
		if (sum != loop || sum > MAX_LOOP_MILLIS) {
			return null;
		}
		int per = perStrip.intValue();
		JsonArray stripsArray = stripsJson.getAsJsonArray();
		int stripCount = (frames + per - 1) / per;
		if (stripsArray.size() != stripCount) {
			return null;
		}
		List<Strip> strips = new ArrayList<>(stripCount);
		long bytes = 0;
		for (int s = 0; s < stripCount; s++) {
			JsonElement e = stripsArray.get(s);
			Strip strip = e != null && e.isJsonObject() ? strip(e.getAsJsonObject(), endpoint) : null;
			if (strip == null || strip.frames() != Math.min(per, frames - s * per)) {
				return null;
			}
			bytes += strip.bytes();
			strips.add(strip);
		}
		if (bytes > MAX_TOTAL_STRIP_BYTES) {
			return null;
		}
		CapeAnimation preview = preview(a.get("preview"), scale.intValue(), durations, endpoint);
		return new CapeAnimation(scale.intValue(), durations, per, strips, preview);
	}

	/**
	 * {@code animation.preview} as an animation of its own, or null when it is absent or isn't exactly one: a scale of 4, 8 or 16
	 * below the animation's, its frame size, {@code frames_per_strip} and strips laid out as the full ones (every frame, the
	 * same durations), each strip checked as a full one.
	 */
	private static @Nullable CapeAnimation preview(@Nullable JsonElement json, int fullScale, int[] durations, StoreEndpoint endpoint) {
		if (json == null || !json.isJsonObject()) {
			return null;
		}
		JsonObject p = json.getAsJsonObject();
		Long scale = JsonFields.integer(p, "scale");
		Long width = JsonFields.integer(p, "width");
		Long height = JsonFields.integer(p, "height");
		Long perStrip = JsonFields.integer(p, "frames_per_strip");
		if (scale == null || !Arrays.stream(PREVIEW_SCALES).anyMatch(s -> s == scale) || scale >= fullScale || width == null
			|| width != ATLAS_WIDTH * scale || height == null || height != ATLAS_HEIGHT * scale || perStrip == null
			|| perStrip != framesPerStrip(height.intValue())) {
			return null;
		}
		JsonElement stripsJson = p.get("strips");
		if (stripsJson == null || !stripsJson.isJsonArray()) {
			return null;
		}
		int frames = durations.length;
		int per = perStrip.intValue();
		JsonArray stripsArray = stripsJson.getAsJsonArray();
		int stripCount = (frames + per - 1) / per;
		if (stripsArray.size() != stripCount) {
			return null;
		}
		List<Strip> strips = new ArrayList<>(stripCount);
		long bytes = 0;
		for (int s = 0; s < stripCount; s++) {
			JsonElement e = stripsArray.get(s);
			Strip strip = e != null && e.isJsonObject() ? strip(e.getAsJsonObject(), endpoint) : null;
			if (strip == null || strip.frames() != Math.min(per, frames - s * per)) {
				return null;
			}
			bytes += strip.bytes();
			strips.add(strip);
		}
		if (bytes > MAX_TOTAL_STRIP_BYTES) {
			return null;
		}
		return new CapeAnimation(scale.intValue(), durations, per, strips, null);
	}

	private static @Nullable Strip strip(JsonObject s, StoreEndpoint endpoint) {
		String sha256 = JsonFields.string(s, "sha256");
		Long bytes = JsonFields.integer(s, "bytes");
		Long frames = JsonFields.integer(s, "frames");
		String url = JsonFields.string(s, "url");
		if (sha256 == null || bytes == null || bytes < 1 || bytes > MAX_STRIP_BYTES || frames == null || frames < 1 || frames > MAX_FRAMES
			|| url == null || url.length() > 2048) {
			return null;
		}
		String hash = sha256.toLowerCase(Locale.ROOT);
		if (!SHA256.matcher(hash).matches()) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			return null;
		}
		if (!endpoint.allowsAsset(uri)) {
			return null;
		}
		return new Strip(uri, hash, bytes.intValue(), frames.intValue(), cdnUrl(s, endpoint));
	}

	/**
	 * A file's {@code cdn_url} (Flint's CDN: the same file by the same hash), or null when it is absent or not an address
	 * {@code endpoint} allows: the file then comes from its {@code url} only.
	 */
	static @Nullable URI cdnUrl(JsonObject file, StoreEndpoint endpoint) {
		String cdn = JsonFields.string(file, "cdn_url");
		if (cdn == null || cdn.isEmpty() || cdn.length() > 2048) {
			return null;
		}
		try {
			URI uri = new URI(cdn);
			return endpoint.allowsAsset(uri) ? uri : null;
		} catch (URISyntaxException e) {
			return null;
		}
	}

	/** A whole number (no fraction, within ±2^53), or null. */
	private static @Nullable Long integer(@Nullable JsonElement value) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		double number = value.getAsDouble();
		if (!Double.isFinite(number) || number != Math.rint(number) || Math.abs(number) > 9.007199254740992E15) {
			return null;
		}
		return (long) number;
	}

	private static String key(int scale, int[] durations, List<Strip> strips) {
		StringBuilder text = new StringBuilder("v1:").append(scale).append(':');
		for (int d : durations) {
			text.append(d).append(',');
		}
		for (Strip s : strips) {
			text.append(':').append(s.sha256());
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof CapeAnimation a && a.scale == scale && a.framesPerStrip == framesPerStrip && Arrays.equals(a.durations, durations)
			&& a.strips.equals(strips) && java.util.Objects.equals(a.preview, preview);
	}

	@Override
	public int hashCode() {
		return key.hashCode();
	}

	@Override
	public String toString() {
		return "animation " + key + " (" + frameCount() + " frames at " + scale + " px per unit, " + loopMillis() + " ms a loop, "
			+ strips.size() + " strips" + (preview == null ? "" : ", preview at " + preview.scale) + ")";
	}
}
