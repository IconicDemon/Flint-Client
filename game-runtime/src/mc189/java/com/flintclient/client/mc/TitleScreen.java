package com.flintclient.client.mc;

/**
 * Newer Minecraft's title screen, for Flint's main menu on Minecraft 1.8.9 (see {@link Component}). Flint's main menu extends
 * it, as it does on every line; {@code new TitleScreen()} itself stands for Minecraft's own title screen, which
 * {@link Minecraft.Gui#setScreen} shows as Minecraft 1.8.9's. Escape does nothing here.
 */
public class TitleScreen extends Screen {
	public TitleScreen() {
		this(false);
	}

	public TitleScreen(boolean fading) {
		super(Component.translatable("narrator.screen.title"));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
