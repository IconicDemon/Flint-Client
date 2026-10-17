package com.flintclient.client.module.servers;

/**
 * The status pings of one stay on the main menu. A ping only starts if the round is still open when a
 * ping thread reaches it; if the round is stopped while a ping is connecting, the connection it opens is
 * closed as soon as the ping returns; and results check {@link #open()} before they are recorded. So
 * nothing started on the menu connects, stays connected or reports once the menu has gone.
 * Thread-safe.
 */
final class PingRound {
	private final Runnable closeConnections;
	private volatile boolean stopped;

	/** {@code closeConnections} disconnects every connection this round's pings opened (from any thread). */
	PingRound(Runnable closeConnections) {
		this.closeConnections = closeConnections;
	}

	boolean open() {
		return !stopped;
	}

	/** {@code ping} wrapped for the ping thread: skipped once the round has stopped, cleaned up if it stops meanwhile. */
	Runnable task(Runnable ping) {
		return () -> {
			if (stopped) {
				return;
			}
			try {
				ping.run();
			} finally {
				// stop() may have closed connections before this ping added its own.
				if (stopped) {
					closeConnections.run();
				}
			}
		};
	}

	/** Ends the round: queued pings won't start and open connections are closed. */
	void stop() {
		stopped = true;
		closeConnections.run();
	}
}
