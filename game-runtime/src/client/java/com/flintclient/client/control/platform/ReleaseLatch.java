package com.flintclient.client.control.platform;

/**
 * Keeps a held key from acting twice. Minecraft clicks a key mapping on the system's key repeats as well as
 * on presses, and closing a screen doesn't reset that, so closing the Control Center with its key and
 * holding on would reopen it as soon as the repeats start. Once armed (when the Control Center opens, or
 * the screen changes), the key's clicks are ignored until the key has been seen up.
 *
 * <p>The key is looked at once per client tick, so a release and a new press inside the same tick (50 ms)
 * read as one long hold and that press is ignored. Render thread only.
 */
public final class ReleaseLatch {
	private boolean armed;

	/** From now on, ignore the key until it has been seen up. */
	public void arm() {
		armed = true;
	}

	/** Notes whether the key is physically held; the first time it's up, the key acts again. */
	public void observe(boolean held) {
		if (!held) {
			armed = false;
		}
	}

	/** Whether the key's clicks are being ignored, because it hasn't been let go since it was armed. */
	public boolean holding() {
		return armed;
	}
}
