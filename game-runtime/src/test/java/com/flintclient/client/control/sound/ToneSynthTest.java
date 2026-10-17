package com.flintclient.client.control.sound;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToneSynthTest {
	private static final int MS = ToneSynth.SAMPLE_RATE / 1000;

	@Test
	void lengthsMatchTheDesign() {
		assertEquals(7497, ToneSynth.open().length, "170 ms at 44.1 kHz");
		assertEquals(4410, ToneSynth.close().length, "100 ms at 44.1 kHz");
	}

	@Test
	void peaksSitAtTheirTargetsAndNeverAbove() {
		double open = ToneSynth.peakDbfs(ToneSynth.open());
		double close = ToneSynth.peakDbfs(ToneSynth.close());
		assertTrue(open <= ToneSynth.OPEN_PEAK_DBFS && open > ToneSynth.OPEN_PEAK_DBFS - 0.05, "open peak " + open);
		assertTrue(close <= ToneSynth.CLOSE_PEAK_DBFS && close > ToneSynth.CLOSE_PEAK_DBFS - 0.05, "close peak " + close);
		assertTrue(close < open, "closing is the lighter sound");
	}

	@Test
	void bothStartAndEndInSilenceWithoutAClick() {
		for (short[] pcm : new short[][] {ToneSynth.open(), ToneSynth.close()}) {
			int peak = peak(pcm);
			assertEquals(0, pcm[0]);
			assertEquals(0, pcm[pcm.length - 1]);
			// The edges stay far below the peak: the attack rises from nothing and the ending fades away.
			for (int i = 0; i < MS / 3; i++) {
				assertTrue(Math.abs(pcm[i]) < peak / 10, "fade in at " + i);
			}
			for (int i = 0; i < MS; i++) {
				assertTrue(Math.abs(pcm[pcm.length - 1 - i]) < peak / 20, "fade out at " + i);
			}
		}
	}

	@Test
	void theWavesAreFiniteAndTheSamplesNeverClip() {
		for (double[] wave : new double[][] {ToneSynth.openWave(), ToneSynth.closeWave()}) {
			for (double sample : wave) {
				assertTrue(Double.isFinite(sample));
			}
		}
		for (short[] pcm : new short[][] {ToneSynth.open(), ToneSynth.close()}) {
			assertTrue(peak(pcm) < Short.MAX_VALUE / 4, "well clear of full scale");
		}
	}

	@Test
	void outputIsDeterministicAndFreshEachCall() {
		short[] first = ToneSynth.open();
		short[] second = ToneSynth.open();
		assertArrayEquals(first, second);
		assertNotSame(first, second, "callers may keep or change their copy");
		assertArrayEquals(ToneSynth.close(), ToneSynth.close());
	}

	@Test
	void openIsAWarmChimeAFifthApartOverABriefLowBody() {
		double[] wave = ToneSynth.openWave();
		double fundamental = power(wave, 40 * MS, wave.length, 880);
		assertTrue(power(wave, 40 * MS, wave.length, 1320) > fundamental / 20, "the fifth above is audible");
		assertTrue(power(wave, 40 * MS, wave.length, 1320) < fundamental, "but quieter than the fundamental");
		assertTrue(power(wave, 40 * MS, wave.length, 600) < fundamental / 50, "nothing in between");
		double bodyEarly = power(wave, 0, 30 * MS, 200);
		double bodyLate = power(wave, 40 * MS, 70 * MS, 200);
		assertTrue(bodyEarly > 100 * bodyLate, "the 200 Hz body lasts about 30 ms");
	}

	@Test
	void theChimeGlidesUpIntoPlace() {
		double[] wave = ToneSynth.openWave();
		// 3% flat at first: early on there is more energy just below 880 Hz than after the glide has settled.
		double flatEarly = power(wave, 4 * MS, 14 * MS, 860) / power(wave, 4 * MS, 14 * MS, 880);
		double flatLate = power(wave, 60 * MS, 70 * MS, 860) / power(wave, 60 * MS, 70 * MS, 880);
		assertTrue(flatEarly > flatLate, "early " + flatEarly + ", late " + flatLate);
	}

	@Test
	void theTickIsAShortBurstAroundThreeKilohertz() {
		double[] tick = new double[ToneSynth.length(0.01)];
		ToneSynth.addTick(tick, 1.0);
		int last = 0;
		for (int i = 0; i < tick.length; i++) {
			if (tick[i] != 0) {
				last = i;
			}
		}
		assertTrue(last < ToneSynth.length(ToneSynth.TICK_SECONDS), "over within 2.5 ms");
		assertEquals(0, tick[0], 1.0E-12, "it starts from silence");
		double around = power(tick, 0, tick.length, ToneSynth.TICK_HZ);
		assertTrue(around > 4 * power(tick, 0, tick.length, 300), "band-limited high, not a low thump");
		assertTrue(around > 4 * power(tick, 0, tick.length, 12_000), "and not a hiss");
	}

	@Test
	void theTickIsTheSameEveryTime() {
		double[] a = new double[200];
		double[] b = new double[200];
		ToneSynth.addTick(a, 1.0);
		ToneSynth.addTick(b, 1.0);
		assertArrayEquals(a, b);
	}

	@Test
	void theTickIsWellUnderThePeak() {
		double[] tickOnly = new double[ToneSynth.length(0.01)];
		ToneSynth.addTick(tickOnly, ToneSynth.gain(ToneSynth.TICK_DBFS - ToneSynth.OPEN_PEAK_DBFS));
		double peak = 0;
		for (double sample : tickOnly) {
			peak = Math.max(peak, Math.abs(sample));
		}
		assertEquals(ToneSynth.gain(-9.0), peak, 1.0E-9, "9 dB under the chime's peak: about -26 dBFS");
	}

	@Test
	void closeGlidesDownward() {
		double[] wave = ToneSynth.closeWave();
		assertTrue(power(wave, 4 * MS, 24 * MS, 1150) > power(wave, 4 * MS, 24 * MS, 900), "starts near 1150 Hz");
		assertTrue(power(wave, 75 * MS, 95 * MS, 900) > power(wave, 75 * MS, 95 * MS, 1150), "ends near 900 Hz");
	}

	@Test
	void littleEndianBytesPutTheLowByteFirst() {
		byte[] bytes = ToneSynth.toLittleEndian(new short[] {0x1234, -2, Short.MIN_VALUE});
		assertArrayEquals(new byte[] {0x34, 0x12, (byte) 0xFE, (byte) 0xFF, 0x00, (byte) 0x80}, bytes);
	}

	@Test
	void silenceHasNoPeak() {
		assertEquals(Double.NEGATIVE_INFINITY, ToneSynth.peakDbfs(new short[8]));
		assertArrayEquals(new short[4], ToneSynth.toPcm(new double[4], -16), "nothing to scale up");
	}

	private static int peak(short[] pcm) {
		int peak = 0;
		for (short sample : pcm) {
			peak = Math.max(peak, Math.abs(sample));
		}
		return peak;
	}

	/** Goertzel: the power of {@code hz} in {@code wave[from, to)}. */
	private static double power(double[] wave, int from, int to, double hz) {
		double coefficient = 2 * Math.cos(2 * Math.PI * hz / ToneSynth.SAMPLE_RATE);
		double previous = 0;
		double beforePrevious = 0;
		for (int i = from; i < to; i++) {
			double current = wave[i] + coefficient * previous - beforePrevious;
			beforePrevious = previous;
			previous = current;
		}
		return previous * previous + beforePrevious * beforePrevious - coefficient * previous * beforePrevious;
	}
}
