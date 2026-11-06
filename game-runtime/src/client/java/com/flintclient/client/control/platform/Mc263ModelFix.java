/*
 * Adapted from Model Gap Fix by MehVahdJukaar (https://github.com/MehVahdJukaar/modelfix-multi), GPL-3.0: the licence text is
 * in META-INF/licenses/GPL-3.0-ModelGapFix.txt. This file and the 1.21.10 ModelGapFixMixin are covered by the GPL-3.0.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.compat.McCompat;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.ModelGapFix;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * Model Gap Fix: the numbers item models are built with (read while models bake, on Minecraft's loading threads), and a
 * resource reload when they change, as the mod reloads on a config change. Only 1.21.10 builds item models with gaps
 * ({@link McCompat#ITEM_MODEL_GAPS}); later versions never ask.
 */
public final class Mc263ModelFix {
	/** The macOS section applies on macOS (some Macs bleed between atlas textures), the default one elsewhere. */
	public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
	private static final int MODULE = Mods.index(ModelGapFix.ID);

	/** What models are built with: off is Minecraft's own (no indent, no expansion, the atlas shrink kept). */
	public record Params(boolean on, double expansion, double indent, double shrink) {
		static final Params OFF = new Params(false, 0, 0, 1);
	}

	/** What the last model bake used (null: none seen yet). */
	private static volatile Params baked;
	private static Params pending;
	private static int stableTicks;

	private Mc263ModelFix() {
	}

	/** The numbers as the module is set now. */
	public static Params current() {
		if (!McCompat.ITEM_MODEL_GAPS || !Mods.on(MODULE)) {
			return Params.OFF;
		}
		ModuleState s = Mods.state(MODULE);
		return MAC ? new Params(true, s.decimal(ModelGapFix.MAC_EXPANSION), s.decimal(ModelGapFix.MAC_INDENT), s.decimal(ModelGapFix.MAC_SHRINK))
			: new Params(true, s.decimal(ModelGapFix.EXPANSION), s.decimal(ModelGapFix.INDENT), 0);
	}

	/** From the model bake: the numbers to build with now (remembered, so a later change knows to reload). */
	public static Params baking() {
		Params p = current();
		baked = p;
		return p;
	}

	/** From the client tick: once the numbers have stayed changed for a second, the resource packs reload with them. */
	public static void tick() {
		if (!McCompat.ITEM_MODEL_GAPS || baked == null) {
			return;
		}
		Params now = current();
		if (now.equals(baked)) {
			pending = null;
			return;
		}
		if (!now.equals(pending)) {
			pending = now;
			stableTicks = 0;
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (++stableTicks >= 20) {
			baked = now;
			pending = null;
			mc.reloadResourcePacks();
		}
	}
}
