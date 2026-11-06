package com.flintclient.client.thirdparty;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Flint's small, optional fixes for other mods a player may have installed ({@code flintclient.thirdparty.mixins.json}):
 * each applies only when its mod is loaded, and a mixin that no longer finds its target in a newer release of that mod is
 * simply skipped (the config isn't required and requires no injection), so the other mod always keeps working.
 *
 * <p>Walksy Lib ({@code walksylib}): its "&lt;mod&gt; &lt;version&gt; is out" toast is not shown (the update stays listed in
 * Walksy Lib's own panel, where it is installed), and on Flint's main menu its other toasts start below the menu's top bar.
 */
public final class ThirdPartyMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.contains(".Walksy")) {
			return FabricLoader.getInstance().isModLoaded("walksylib");
		}
		return false;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
