package com.flintclient.client.ui.kit;

import org.jspecify.annotations.Nullable;

/**
 * A control activated by a click (press and release on it with the left button) or by Enter/Space when
 * focused. Activation plays Minecraft's click and runs the action.
 */
public abstract class Pressable extends Node {
	private @Nullable Runnable action;

	protected Pressable(@Nullable Runnable action) {
		this.action = action;
	}

	public void setAction(@Nullable Runnable action) {
		this.action = action;
	}

	@Override
	public boolean interactive() {
		return true;
	}

	@Override
	public boolean focusable() {
		return true;
	}

	@Override
	public boolean mouseDown(float mx, float my, int button) {
		return button == Kit.LEFT_BUTTON;
	}

	@Override
	public void mouseUp(float mx, float my, int button) {
		// Released outside: the press is cancelled, as with Minecraft's buttons.
		if (button == Kit.LEFT_BUTTON && enabled() && containsPoint(mx, my)) {
			activate();
		}
	}

	@Override
	public void activate() {
		if (!enabled()) {
			return;
		}
		press = 1.0F;
		if (screen != null) {
			screen.playClick();
		}
		if (action != null) {
			action.run();
		}
	}
}
