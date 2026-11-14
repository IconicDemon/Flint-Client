package com.flintclient.client.control.hud.render;

import com.flintclient.client.control.rshift.ModMenuScreen;
import com.flintclient.client.platform.HostScreen;
import com.flintclient.client.platform.HudData;
import com.flintclient.client.platform.MenuHost;
import org.jspecify.annotations.Nullable;

/**
 * The HUD as the Right Shift menu's live preview: while the menu is open over a world on a line that has it
 * ({@link MenuHost#livePreview}), every enabled HUD mod stays on screen where it really is, and a mod with nothing to show right
 * now (Ping in singleplayer, TPS before the server's first report, Effects with none, Armor with none worn) is drawn with its
 * sample values instead, a touch faded, so the player sees how it looks the moment it is switched on. Those samples exist only in
 * the drawing: nothing reads them back, sends them or plays by them, and they stop when the menu closes.
 *
 * <p>The mod card under the pointer also names its module here each frame ({@link #hover}), so the menu can outline that mod on
 * the screen. Field reads and an {@code instanceof} per HUD frame; nothing with the menu closed. Render thread.
 */
public final class HudPreview {
	/** How opaque a mod drawn with sample values is, against the live ones (1). */
	static final float SAMPLE_OPACITY = 0.78F;

	private static @Nullable String hovered;
	private static float hoverAmount;

	private HudPreview() {
	}

	/** Whether the HUD is being drawn as the menu's preview now (the Right Shift window is the screen shown, over a world). */
	public static boolean active() {
		return MenuHost.livePreview() && HostScreen.current() instanceof ModMenuScreen && HudData.inWorld();
	}

	/** A mod card under the pointer this frame, how strongly (its hover, 0 to 1); the strongest one this frame wins. */
	public static void hover(String moduleId, float amount) {
		if (amount > hoverAmount) {
			hovered = moduleId;
			hoverAmount = amount;
		}
	}

	/** The module a card named this frame (or null), read once: the frame's hover is cleared. */
	public static @Nullable String takeHovered() {
		String id = hovered;
		hovered = null;
		hoverAmount = 0;
		return id;
	}

	/** How strongly {@link #takeHovered}'s module is hovered; ask before taking it. */
	public static float hoverAmount() {
		return hoverAmount;
	}
}
