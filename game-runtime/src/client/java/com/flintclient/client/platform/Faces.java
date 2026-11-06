package com.flintclient.client.platform;

import com.flintclient.client.module.account.AccountSkin;
import net.minecraft.client.gui.components.PlayerFaceExtractor;

/**
 * The players the menus show: the signed-in player (the header: their name, id and face) and other players by UUID (the
 * Friends screen), each as their skin's face with its hat layer. Platform class (see {@link Clock}).
 */
public final class Faces {
	private Faces() {
	}

	/** The signed-in player's name. */
	public static String ownName() {
		return AccountSkin.username();
	}

	/** The signed-in player's UUID, 32 lowercase hex digits. */
	public static String ownId() {
		return com.flintclient.client.module.account.AccountSwitcher.currentId();
	}

	/** The signed-in player's face, {@code size} pixels square at ({@code x}, {@code y}) in the frame's pixel space. */
	public static void own(Frame frame, int x, int y, int size) {
		PlayerFaceExtractor.extractRenderState(frame.graphics(), AccountSkin.current(), x, y, size);
	}

	/**
	 * Another player's face by UUID (either spelling), looked up as Minecraft looks up any player's skin; their default skin's
	 * until it has loaded.
	 */
	public static void of(Frame frame, String uuid, int x, int y, int size) {
		PlayerFaceExtractor.extractRenderState(frame.graphics(), AccountSkin.of(uuid), x, y, size);
	}
}
