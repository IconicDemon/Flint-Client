package com.flintclient.client.compat.render;

/**
 * 1.19.4's {@code Font.DisplayMode} on Minecraft 1.19.2, whose Font.drawInBatch takes a see-through flag instead: what the
 * shared sources pass and compare stays the same, and {@link #seeThrough} is the flag 1.19.2's font takes (it has no polygon
 * offset mode, which draws as a normal one there).
 */
public enum DisplayMode {
	NORMAL,
	SEE_THROUGH,
	POLYGON_OFFSET;

	/** The mode 1.19.2's see-through flag stands for. */
	public static DisplayMode of(boolean seeThrough) {
		return seeThrough ? SEE_THROUGH : NORMAL;
	}

	/** The see-through flag 1.19.2's Font.drawInBatch takes for this mode. */
	public boolean seeThrough() {
		return this == SEE_THROUGH;
	}
}
