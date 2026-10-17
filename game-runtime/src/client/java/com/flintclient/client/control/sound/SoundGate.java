package com.flintclient.client.control.sound;

/**
 * Keeps the interface sounds from piling up: a cue within {@value #QUIET_MILLIS} ms of the last one that played
 * (an open right after a close, or the reverse, as when the Control Center is turned round mid-close) doesn't play,
 * so the sound already playing finishes undisturbed instead of restarting. Only cues that played count. Pure and
 * allocation-free.
 */
public final class SoundGate {
	public static final long QUIET_MILLIS = 150;

	private long lastPlayed;
	private boolean played;

	/** Whether a cue may play at {@code nowMillis}; when it may, that is remembered as the last one. */
	public boolean allow(long nowMillis) {
		if (played && nowMillis - lastPlayed < QUIET_MILLIS && nowMillis >= lastPlayed) {
			return false;
		}
		played = true;
		lastPlayed = nowMillis;
		return true;
	}
}
