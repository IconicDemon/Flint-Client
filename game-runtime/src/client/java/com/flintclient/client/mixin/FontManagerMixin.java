package com.flintclient.client.mixin;

import com.flintclient.client.FlintClient;
import com.flintclient.client.render.FlintFonts;
import com.flintclient.client.render.FontPreload;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.font.GlyphProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creates Flint's font sets on first use instead of declaring one per text role and GUI scale up front.
 * Minecraft keeps a font set (glyph tables and a glyph texture) for every declared font; declaring
 * them all would cost tens of megabytes for scales that are never shown. Only the roles drawn at the
 * current GUI scale are ever created (a handful), and they're dropped with the others on a resource
 * reload.
 *
 * <p>The hook changes getFontSetRaw's result instead of cancelling at its start: vanilla only remembers
 * the last font asked for, so this runs on every font switch (dozens of times per menu frame), and an
 * injected callback would allocate its callback info each time. Vanilla returns
 * {@code fontSets.getOrDefault(id, missingFontSet)}, and missingFontSet is never stored in fontSets, so
 * the result is replaced only when no set by that name exists: the same cases the old hook handled, with
 * the same result for every id.
 *
 * <p>The reload and close hooks drive {@link FontPreload} (the {@code fontPreload} module).
 */
@Mixin(FontManager.class)
abstract class FontManagerMixin {
	@Shadow
	@Final
	private FontSet missingFontSet;

	@Shadow
	@Final
	private Map<Identifier, FontSet> fontSets;

	@Shadow
	@Final
	private List<GlyphProvider> providersToClose;

	@Shadow
	private FontSet createFontSet(Identifier id, List<GlyphProvider.Conditional> providers, Set<FontOption> options) {
		throw new AssertionError();
	}

	@ModifyReturnValue(method = "getFontSetRaw", at = @At("RETURN"))
	private FontSet flint$createOnFirstUse(FontSet found, Identifier id) {
		if (found != missingFontSet || !FlintClient.MOD_ID.equals(id.getNamespace()) || fontSets.containsKey(id)) {
			return found;
		}
		FlintFonts.Spec spec = FlintFonts.spec(id);
		if (spec == null) {
			return found;
		}
		boolean timing = FontPreload.timing();
		long start = timing ? System.nanoTime() : 0L;
		try {
			GlyphProvider provider = FontPreload.take(id);
			if (provider == null) {
				provider = spec.load(Minecraft.getInstance().getResourceManager());
			}
			providersToClose.add(provider);
			FontSet set = createFontSet(id, List.of(new GlyphProvider.Conditional(provider, FontOption.Filter.ALWAYS_PASS)), Set.of());
			fontSets.put(id, set);
			if (timing) {
				FontPreload.logCreated(id, System.nanoTime() - start);
			}
			return set;
		} catch (Exception e) {
			FlintClient.LOGGER.warn("Couldn't load Flint font {}", id, e);
			return found;
		}
	}

	// Before anything is closed: what was prepared read the resources this reload replaces.
	@Inject(method = "apply", at = @At("HEAD"))
	private void flint$reloadStarting(CallbackInfo callback) {
		FontPreload.reloadStarting();
	}

	@Inject(method = "apply", at = @At("RETURN"))
	private void flint$reloaded(CallbackInfo callback) {
		FontPreload.reloaded(fontSets::containsKey);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void flint$closing(CallbackInfo callback) {
		FontPreload.shutdown();
	}
}
