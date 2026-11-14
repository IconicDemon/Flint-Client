package com.flintclient.legacy.mixin.swap;

import net.minecraft.client.resource.language.Locale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The hot loader on 1.8.9 (legacy/swap/HotLoader189): a language's translations, by key. */
@Mixin(Locale.class)
public interface HotLoadLocaleAccess {
	@Accessor("translations")
	java.util.Map<String, String> flint$translations();
}
