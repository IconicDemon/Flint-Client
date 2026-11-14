package com.flintclient.client.mc;

/** A mouse button and the modifier keys held with it, as newer Minecraft names it (see {@link MouseButtonEvent}). */
public record MouseButtonInfo(int button, int modifiers) {
}
