package com.flintclient.client.control.sound;

/**
 * Flint's two interface sounds, synthesised rather than shipped, so they're original, cost no asset and work without
 * any resource pack. Both are short and quiet on purpose: they confirm an action and get out of the way.
 * <ul>
 * <li><b>Open</b>, a soft tactile tick and a warm chime: 2.5 ms of noise band-limited around 3 kHz at about -26 dBFS
 * (the touch), a 200 Hz body that dies away within 30 ms (the weight), and two sine partials a fifth apart, 880 Hz
 * and 1320 Hz, with a faint 1326 Hz shimmer 30 dB down that beats slowly against the upper one. The chime's pitch
 * glides up 3% into place over its first 40 ms; 4 ms attack, then an exponential decay with a time constant of about
 * 55 ms. 170 ms, peaking at -17 dBFS.</li>
 * <li><b>Close</b>: the same tick, then one partial gliding down from 1150 Hz to 900 Hz, time constant about 35 ms.
 * 100 ms, peaking at -20 dBFS, so closing sounds lighter than opening.</li>
 * </ul>
 * Every attack and the end of each sound are raised-cosine fades, so neither starts or stops with a click: the first
 * and last samples are exactly zero. The noise comes from a fixed-seed generator and {@link StrictMath} keeps the
 * maths bit-identical, so the sounds are the same on every machine. The output is mono 16-bit PCM at 44.1 kHz.
 * Nothing here touches Minecraft.
 */
public final class ToneSynth {
	public static final int SAMPLE_RATE = 44_100;
	public static final double OPEN_PEAK_DBFS = -17.0;
	public static final double CLOSE_PEAK_DBFS = -20.0;
	/** The tick's peak, in dBFS. */
	public static final double TICK_DBFS = -26.0;
	static final double OPEN_SECONDS = 0.170;
	static final double CLOSE_SECONDS = 0.100;
	static final double TICK_SECONDS = 0.0025;
	static final double TICK_HZ = 3000.0;
	/** Long enough to fade the decay's tail out smoothly, short enough not to clip the sound's body. */
	private static final double FADE_OUT_SECONDS = 0.014;
	private static final double FULL_SCALE = Short.MAX_VALUE;
	private static final long NOISE_SEED = 0x5F1A_4E2B_9C3D_7781L;

	private ToneSynth() {
	}

	/** The open sound as signed 16-bit samples. A new array each call. */
	public static short[] open() {
		return toPcm(openWave(), OPEN_PEAK_DBFS);
	}

	/** The close sound as signed 16-bit samples. A new array each call. */
	public static short[] close() {
		return toPcm(closeWave(), CLOSE_PEAK_DBFS);
	}

	/** The samples as signed 16-bit little-endian bytes (the layout of a WAV file's data). */
	public static byte[] toLittleEndian(short[] pcm) {
		byte[] bytes = new byte[pcm.length * 2];
		for (int i = 0; i < pcm.length; i++) {
			bytes[2 * i] = (byte) pcm[i];
			bytes[2 * i + 1] = (byte) (pcm[i] >> 8);
		}
		return bytes;
	}

	/** A peak level in dBFS, relative to {@link Short#MAX_VALUE}. */
	public static double peakDbfs(short[] pcm) {
		int peak = 0;
		for (short sample : pcm) {
			peak = Math.max(peak, Math.abs(sample));
		}
		return peak == 0 ? Double.NEGATIVE_INFINITY : 20 * StrictMath.log10(peak / FULL_SCALE);
	}

	/**
	 * The open sound before scaling: the tonal part peaks at 1 (the sound's own peak), the tick sits at its level
	 * relative to that.
	 */
	static double[] openWave() {
		double[] chime = new double[length(OPEN_SECONDS)];
		gliding(chime, 880.0, 1.0, 0.004, 0.055);
		gliding(chime, 1320.0, 0.42, 0.004, 0.045);
		gliding(chime, 1326.0, gain(-30.0), 0.004, 0.055);
		body(chime, 200.0, gain(-10.0), 0.003, 0.030);
		normalise(chime);
		addTick(chime, gain(TICK_DBFS - OPEN_PEAK_DBFS));
		fadeOut(chime, FADE_OUT_SECONDS);
		return chime;
	}

	/** The close sound before scaling, as {@link #openWave}. */
	static double[] closeWave() {
		double[] wave = new double[length(CLOSE_SECONDS)];
		glidingBetween(wave, 1150.0, 900.0, 0.003, 0.035);
		normalise(wave);
		addTick(wave, gain(TICK_DBFS - CLOSE_PEAK_DBFS));
		fadeOut(wave, FADE_OUT_SECONDS);
		return wave;
	}

	static int length(double seconds) {
		return (int) Math.round(seconds * SAMPLE_RATE);
	}

	/** Linear gain for a level in dB. */
	static double gain(double db) {
		return StrictMath.pow(10.0, db / 20.0);
	}

	/**
	 * Adds a sine at {@code hz} whose pitch glides up into place: from 3% flat to {@code hz} over the first 40 ms
	 * (eased), then steady; a raised-cosine attack and an exponential decay with time constant {@code tau}. The phase
	 * is accumulated sample by sample, so the glide has no jumps.
	 */
	private static void gliding(double[] wave, double hz, double amplitude, double attack, double tau) {
		double glide = 0.040;
		double phase = 0;
		for (int i = 0; i < wave.length; i++) {
			double t = (double) i / SAMPLE_RATE;
			wave[i] += amplitude * attackDecay(t, attack, tau) * StrictMath.sin(phase);
			double settle = t < glide ? rise(t / glide) : 1;
			double now = hz * (0.97 + 0.03 * settle);
			phase += 2 * Math.PI * now / SAMPLE_RATE;
		}
	}

	/** Adds a sine gliding from {@code fromHz} to {@code toHz} over the whole wave, evenly in pitch. */
	private static void glidingBetween(double[] wave, double fromHz, double toHz, double attack, double tau) {
		double duration = (double) wave.length / SAMPLE_RATE;
		double phase = 0;
		for (int i = 0; i < wave.length; i++) {
			double t = (double) i / SAMPLE_RATE;
			wave[i] += attackDecay(t, attack, tau) * StrictMath.sin(phase);
			double hz = fromHz * StrictMath.pow(toHz / fromHz, t / duration);
			phase += 2 * Math.PI * hz / SAMPLE_RATE;
		}
	}

	/** Adds a short low sine that rises over {@code attack} and falls back to silence by {@code length}. */
	private static void body(double[] wave, double hz, double amplitude, double attack, double length) {
		int end = Math.min(wave.length, length(length));
		for (int i = 0; i < end; i++) {
			double t = (double) i / SAMPLE_RATE;
			double envelope = t < attack ? rise(t / attack) : 1 - rise((t - attack) / (length - attack));
			wave[i] += amplitude * envelope * StrictMath.sin(2 * Math.PI * hz * t);
		}
	}

	/**
	 * Adds the tick: {@value #TICK_SECONDS} s of white noise from a fixed-seed generator, band-limited around
	 * {@value #TICK_HZ} Hz by a band-pass filter (Q 1.4), shaped by a raised-cosine window and scaled to peak at
	 * {@code peak}.
	 */
	static void addTick(double[] wave, double peak) {
		int n = Math.min(wave.length, length(TICK_SECONDS));
		double[] tick = new double[n];
		// RBJ band-pass (constant 0 dB peak gain).
		double w0 = 2 * Math.PI * TICK_HZ / SAMPLE_RATE;
		double alpha = StrictMath.sin(w0) / (2 * 1.4);
		double a0 = 1 + alpha;
		double b0 = alpha / a0;
		double b2 = -alpha / a0;
		double a1 = -2 * StrictMath.cos(w0) / a0;
		double a2 = (1 - alpha) / a0;
		long seed = NOISE_SEED;
		double x1 = 0;
		double x2 = 0;
		double y1 = 0;
		double y2 = 0;
		double max = 0;
		for (int i = 0; i < n; i++) {
			seed ^= seed << 13;
			seed ^= seed >>> 7;
			seed ^= seed << 17;
			double x = ((seed >>> 11) * 0x1.0p-53) * 2 - 1;
			double y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2;
			x2 = x1;
			x1 = x;
			y2 = y1;
			y1 = y;
			// A raised-cosine (Hann) window: silent at both ends, so the tick never clicks.
			double window = 0.5 - 0.5 * StrictMath.cos(2 * Math.PI * i / Math.max(1, n - 1));
			tick[i] = y * window;
			max = Math.max(max, Math.abs(tick[i]));
		}
		if (max == 0) {
			return;
		}
		for (int i = 0; i < n; i++) {
			wave[i] += tick[i] * peak / max;
		}
	}

	/** Scales the wave so its largest sample is exactly 1. */
	private static void normalise(double[] wave) {
		double peak = 0;
		for (double sample : wave) {
			peak = Math.max(peak, Math.abs(sample));
		}
		if (peak > 0) {
			for (int i = 0; i < wave.length; i++) {
				wave[i] /= peak;
			}
		}
	}

	private static double attackDecay(double t, double attack, double tau) {
		return t < attack ? rise(t / attack) : StrictMath.exp(-(t - attack) / tau);
	}

	/** Raised cosine from 0 at {@code x = 0} to 1 at {@code x = 1}. */
	private static double rise(double x) {
		return 0.5 - 0.5 * StrictMath.cos(Math.PI * Math.min(1, Math.max(0, x)));
	}

	/** Multiplies the last {@code seconds} by a raised cosine falling to exactly 0 at the last sample. */
	private static void fadeOut(double[] wave, double seconds) {
		int fade = Math.min(wave.length, length(seconds));
		int start = wave.length - fade;
		for (int k = 0; k < fade; k++) {
			wave[start + k] *= 1 - rise(fade == 1 ? 1 : (double) k / (fade - 1));
		}
	}

	/** Scales the wave so its largest sample sits exactly at {@code peakDbfs}, then rounds to 16 bits. */
	static short[] toPcm(double[] wave, double peakDbfs) {
		double peak = 0;
		for (double sample : wave) {
			peak = Math.max(peak, Math.abs(sample));
		}
		short[] pcm = new short[wave.length];
		if (peak == 0 || !Double.isFinite(peak)) {
			return pcm;
		}
		// Half a step under the target, so rounding never lifts the peak sample above it.
		double target = gain(peakDbfs) * FULL_SCALE;
		double scale = (target - 0.5) / peak;
		for (int i = 0; i < wave.length; i++) {
			pcm[i] = (short) Math.round(wave[i] * scale);
		}
		return pcm;
	}
}
