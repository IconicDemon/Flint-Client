package com.flintclient.client.mc;

/** The language list (Options › Language), as newer Minecraft names it: Minecraft 1.8.9's (see {@link Component}). */
public final class LanguageSelectScreen extends net.minecraft.client.gui.screen.menu.options.LanguageOptionsScreen {
	public LanguageSelectScreen(net.minecraft.client.gui.screen.Screen parent, Minecraft.Options options, Object languages) {
		super(parent, options.game(), (net.minecraft.client.resource.language.LanguageManager) languages);
	}
}
