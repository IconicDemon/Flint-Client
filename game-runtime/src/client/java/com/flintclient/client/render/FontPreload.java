package com.flintclient.client.render;

import com.flintclient.client.FlintClient;
import com.flintclient.client.graphics.Preparations;
import com.mojang.blaze3d.font.GlyphProvider;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The {@code fontPreload} module: after each resource reload and each GUI scale change, every Flint text
 * role's TrueType file is read, its FreeType face created and its glyphs loaded on Minecraft's worker
 * threads, so the frame that first draws a role only builds the font set (glyph tables and texture pages,
 * which stay on the render thread, as today). A role asked for before a worker has started on it is made
 * right there, exactly as without the module; one a worker is on is waited for.
 *
 * <p>Why the workers may do this: it is what FontManager's own reload does for every font on the same
 * executor. The file is read into the provider's own memory, the face is created under FreeType's library
 * lock, and each glyph load is synchronised on its face and published through a volatile field. Drawing a
 * glyph for the first time (its upload) loads it into the face again without that lock, so a provider
 * reaches the render thread only once its job has finished ({@link Preparations}), and a stale one is
 * closed only once its job has ended. Closing happens under the library lock too.
 *
 * <p>Off (the module not running), every call here does nothing and font sets are made as before. These
 * calls run inside FontManager's reload and close and Minecraft's resize, so a failure here turns the
 * module off for the session instead of reaching them. Render thread only.
 */
public final class FontPreload {
	private static final Preparations<Identifier, GlyphProvider> PREPARED = new Preparations<>();
	private static boolean enabled;
	private static boolean timing;
	/** Whether FontManager already has a font set by that name; null until its first reload. */
	private static @Nullable Predicate<Identifier> created;

	private FontPreload() {
	}

	/** Turns the module on; set once at start-up, before the first resource reload. */
	public static void enable() {
		enabled = true;
	}

	/** The developer font timing log ({@code dev.fontTiming}). */
	public static void logTimings() {
		timing = true;
	}

	public static boolean timing() {
		return timing;
	}

	/** From the start of FontManager.apply: anything prepared read the resources being replaced. */
	public static void reloadStarting() {
		if (enabled) {
			try {
				PREPARED.expire();
			} catch (RuntimeException e) {
				turnOff(e);
			}
		}
	}

	/** From the end of FontManager.apply, with its own font-set lookup. */
	public static void reloaded(Predicate<Identifier> fontSetExists) {
		created = fontSetExists;
		if (enabled) {
			try {
				prepare(Minecraft.getInstance().getWindow().getGuiScale());
			} catch (RuntimeException e) {
				turnOff(e);
			}
		}
	}

	/** From Minecraft.resizeGui, with the GUI scale it's about to apply (often unchanged). */
	public static void guiScaleChosen(int guiScale) {
		if (enabled && created != null) {
			try {
				prepare(guiScale);
			} catch (RuntimeException e) {
				turnOff(e);
			}
		}
	}

	/** Fonts are made on the render thread from now on; what was prepared is dropped (closed once its job ends). */
	private static void turnOff(RuntimeException e) {
		FlintClient.LOGGER.warn("Flint font preparation failed; it's off for the rest of this session", e);
		enabled = false;
		try {
			PREPARED.expire();
		} catch (RuntimeException ignored) {
			// Already failing: what's left is closed at shutdown, or not at all.
		}
	}

	private static void prepare(int guiScale) {
		// As Canvas and FlintFonts.forScale name the fonts: at least 1, larger than MAX_SCALE shares it.
		int scale = Math.clamp(Math.max(1, guiScale), 1, FlintFonts.MAX_SCALE);
		Predicate<Identifier> exists = created;
		if (exists == null || !PREPARED.switchTo(scale)) {
			return;
		}
		ResourceManager resources = Minecraft.getInstance().getResourceManager();
		for (FlintFonts role : FlintFonts.values()) {
			if (!role.menuRole()) {
				continue;
			}
			Identifier id = role.fontId(scale);
			FlintFonts.Spec spec = FlintFonts.spec(id);
			// A font set made at this scale before (since the last reload) is still there.
			if (spec != null && !exists.test(id)) {
				PREPARED.start(id, () -> spec.loadWithGlyphs(resources), Util.backgroundExecutor());
			}
		}
	}

	/**
	 * The provider prepared for {@code id}, which the caller now owns (it goes to FontManager's
	 * {@code providersToClose}); null when none was prepared or preparing it failed.
	 */
	public static @Nullable GlyphProvider take(Identifier id) {
		return enabled ? PREPARED.take(id) : null;
	}

	/** One developer timing line for a font set just made. */
	public static void logCreated(Identifier id, long nanos) {
		String how = switch (enabled ? PREPARED.lastOutcome() : Preparations.Outcome.NONE) {
			case NONE -> "loaded on the render thread";
			case READY -> "prepared on a worker";
			case WAITED -> "waited for a worker";
			case MADE_HERE -> "prepared on the render thread, no worker had started it";
			case FAILED -> "preparing failed, loaded on the render thread";
		};
		FlintClient.LOGGER.info("Flint font {}: {} ns on the render thread ({})", id, nanos, how);
	}

	/**
	 * From the start of FontManager.close, before Minecraft shuts FreeType down. Also after the module was
	 * turned off (stale jobs may still be running), and nothing starts afterwards.
	 */
	public static void shutdown() {
		enabled = false;
		try {
			PREPARED.shutdown();
		} catch (RuntimeException e) {
			FlintClient.LOGGER.warn("Couldn't finish Flint's font preparations at shutdown", e);
		}
	}
}
