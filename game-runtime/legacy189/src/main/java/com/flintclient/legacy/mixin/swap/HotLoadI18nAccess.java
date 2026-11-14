package com.flintclient.legacy.mixin.swap;

import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.resource.language.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The hot loader on 1.8.9 (legacy/swap/HotLoader189): the client translations in use. */
@Mixin(I18n.class)
public interface HotLoadI18nAccess {
	@Accessor("locale")
	static Locale flint$locale() {
		throw new AssertionError();
	}
}
