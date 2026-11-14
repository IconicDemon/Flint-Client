package com.flintclient.qa;

import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * Performance tours: {@code call com.flintclient.qa.QaPerf.server} answers the integrated server's tick times (its last 100
 * ticks: mean, max, in ms), so a tour can compare TPS with and without what it measures. Read reflectively (the accessor's
 * name differs across the lines this file builds for). Dev-only.
 */
public final class QaPerf {
	private QaPerf() {
	}

	public static String server() {
		Object server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return "no integrated server";
		}
		try {
			long[] times = (long[]) server.getClass().getMethod("getTickTimesNanos").invoke(server);
			long sum = 0;
			long max = 0;
			int n = 0;
			for (long t : times) {
				if (t > 0) {
					sum += t;
					max = Math.max(max, t);
					n++;
				}
			}
			return String.format(Locale.ROOT, "server mspt mean=%.3f max=%.3f over %d ticks", n == 0 ? 0 : sum / 1e6 / n, max / 1e6, n);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "server tick times unavailable: " + e;
		}
	}
}
