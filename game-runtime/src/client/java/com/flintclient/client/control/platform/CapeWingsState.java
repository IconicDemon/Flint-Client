package com.flintclient.client.control.platform;

/**
 * Room on a living thing's render state (through {@code mixin/feature/LivingEntityRenderStateMixin}) for whether its elytra is
 * drawn as its Flint Store cape ({@link Mc263CapeElytra}): set by the elytra layer as it submits the wings, read by the elytra
 * model as it is set up for them, so both decide the same way for the same player in the same frame. Render thread.
 */
public interface CapeWingsState {
	boolean flint$capeWings();

	void flint$capeWings(boolean capeWings);
}
