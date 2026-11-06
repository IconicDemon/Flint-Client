package com.flintclient.client.compat;

/**
 * The movement keys held this tick, as 1.21.2's {@code Input} record. 1.21.1 keeps them as fields of the player's input
 * (and sprint on its key mapping): mixin.feature.KeyboardInputMixin reads them into this and applies what comes back.
 */
public record Input(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
	/** Whether a latched Toggle Sprint holds sprint this tick while the key itself is up (LocalPlayerSprintMixin reads it). */
	public static boolean latchedSprint;
}
