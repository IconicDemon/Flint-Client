/**
 * The modules beyond the original eight HUD modules: each class declares one module's settings and its
 * {@link com.flintclient.client.control.model.ModuleDescriptor}. Pure data, free of Minecraft types, shared by every
 * Minecraft version; the behaviour lives in the renderer ({@code control.hud.render}) and the features
 * ({@code control.feature}), which reach Minecraft only through the version layer.
 */
package com.flintclient.client.control.model.mods;
