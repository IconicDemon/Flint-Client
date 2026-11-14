package com.flintclient.qa;

import java.nio.file.Path;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * A Java Flight Recorder recording of just part of a tour (a frames step, say), so two runs' profiles cover the same moments.
 * Dev-only.
 *
 * <ul>
 * <li>{@code call com.flintclient.qa.QaJfr.start}: starts recording with the JDK's "profile" settings.</li>
 * <li>{@code call com.flintclient.qa.QaJfr.stop NAME}: stops and writes the run folder's NAME.jfr.</li>
 * </ul>
 */
public final class QaJfr {
	private static Recording recording;

	private QaJfr() {
	}

	public static String start() throws Exception {
		if (recording != null) {
			return "already recording";
		}
		recording = new Recording(Configuration.getConfiguration("profile"));
		recording.start();
		return "recording";
	}

	public static String stop(String name) throws Exception {
		if (recording == null) {
			return "not recording";
		}
		Recording done = recording;
		recording = null;
		done.stop();
		Path file = Path.of(System.getProperty("flintqa.out", ".")).resolve(name + ".jfr");
		done.dump(file);
		done.close();
		return "wrote " + file;
	}
}
