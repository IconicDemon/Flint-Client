package com.flintclient.client.mixin;

import com.flintclient.client.FlintClient;
import com.flintclient.client.compat.render.FontManagerBridge;
import com.flintclient.client.compat.render.OwnedGlyphProvider;
import com.flintclient.client.platform.FontSets;
import com.flintclient.client.render.FontPreload;
import com.mojang.blaze3d.font.GlyphProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creates Flint's font sets on first use instead of declaring one per text role and GUI scale up front.
 * Minecraft keeps a font set (glyph tables and a glyph texture) for every declared font; declaring
 * them all would cost tens of megabytes for scales that are never shown. Only the roles drawn at the
 * current GUI scale are ever created (a handful), and they're dropped with the others on a resource
 * reload.
 *
 * <p>Minecraft 1.19.2 hands each Font it makes a lookup ({@code fontSets.getOrDefault(id, missingFontSet)}, after its renames)
 * that the font asks for every glyph: the lookup is wrapped as the font is made, and its result is replaced only when no set
 * by that name exists (missingFontSet is never stored in fontSets), so every other id gets exactly what it got. A Flint font
 * that fails to load isn't tried again until the next reload (it would be, glyph by glyph). 1.19.2's font set closes only the
 * glyph providers it keeps, so Flint's are closed here after a reload and at shutdown ({@link OwnedGlyphProvider}).
 *
 * <p>The reload and close hooks drive {@link FontPreload} (the {@code fontPreload} module); 1.19.2's reload is its anonymous
 * reload listener's (FontManagerReloadMixin).
 */
@Mixin(FontManager.class)
abstract class FontManagerMixin implements FontManagerBridge {
	@Shadow
	@Final
	private FontSet missingFontSet;

	@Shadow
	@Final
	private Map<ResourceLocation, FontSet> fontSets;

	@Shadow
	@Final
	private TextureManager textureManager;

	/** The glyph providers of the Flint font sets made since the last reload. */
	@Unique
	private final List<GlyphProvider> flint$providers = new ArrayList<>();

	/** Flint fonts that failed to load since the last reload. */
	@Unique
	private final Set<ResourceLocation> flint$failed = new HashSet<>();

	@ModifyArg(method = {"createFont", "createFontFilterFishy"}, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/Font;<init>(Ljava/util/function/Function;Z)V"), index = 0)
	private Function<ResourceLocation, FontSet> flint$lookup(Function<ResourceLocation, FontSet> lookup) {
		return id -> flint$createOnFirstUse(lookup.apply(id), id);
	}

	@Unique
	private FontSet flint$createOnFirstUse(FontSet found, ResourceLocation id) {
		if (found != missingFontSet || !FlintClient.MOD_ID.equals(id.getNamespace()) || fontSets.containsKey(id) || flint$failed.contains(id)) {
			return found;
		}
		FontSets.Spec spec = FontSets.spec(id);
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
			provider = new OwnedGlyphProvider(provider);
			flint$providers.add(provider);
			List<GlyphProvider> providers = new ArrayList<>(2);
			providers.add(provider);
			GlyphProvider fallback = spec.loadFallback(Minecraft.getInstance().getResourceManager());
			if (fallback != null) {
				fallback = new OwnedGlyphProvider(fallback);
				flint$providers.add(fallback);
				providers.add(fallback);
			}
			FontSet set = new FontSet(textureManager, id);
			set.reload(providers);
			fontSets.put(id, set);
			if (timing) {
				FontPreload.logCreated(id, System.nanoTime() - start);
			}
			return set;
		} catch (Exception e) {
			FlintClient.LOGGER.warn("Couldn't load Flint font {}", id, e);
			flint$failed.add(id);
			return found;
		}
	}

	@Override
	public boolean flint$hasFontSet(ResourceLocation id) {
		return fontSets.containsKey(id);
	}

	@Override
	public void flint$reloaded() {
		flint$closeProviders();
		flint$failed.clear();
	}

	@Unique
	private void flint$closeProviders() {
		for (GlyphProvider provider : flint$providers) {
			try {
				provider.close();
			} catch (RuntimeException e) {
				FlintClient.LOGGER.warn("Couldn't close a Flint font", e);
			}
		}
		flint$providers.clear();
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void flint$closing(CallbackInfo callback) {
		FontPreload.shutdown();
	}

	// After the font sets closed the providers they kept: the rest of Flint's.
	@Inject(method = "close", at = @At("RETURN"))
	private void flint$closed(CallbackInfo callback) {
		flint$closeProviders();
	}
}
