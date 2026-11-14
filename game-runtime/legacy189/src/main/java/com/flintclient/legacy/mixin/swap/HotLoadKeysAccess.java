package com.flintclient.legacy.mixin.swap;

import java.util.List;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The hot loader on 1.8.9 (legacy/swap/HotLoader189): every key binding made (a new one registers itself here). */
@Mixin(KeyBinding.class)
public interface HotLoadKeysAccess {
	@Accessor("ALL")
	static List<KeyBinding> flint$all() {
		throw new AssertionError();
	}
}
