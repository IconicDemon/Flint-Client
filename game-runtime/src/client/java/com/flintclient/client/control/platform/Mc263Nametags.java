package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.VisualMods.Nametags;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

/**
 * Minecraft 26.3's side of Nametags beyond your own one in third person: hiding them by key, named mobs and pets always
 * named, ping after players' names, your own colour, and how a nametag is drawn (size, shadow, opacity, background).
 * Minecraft's visibility rules (teams, invisibility, F1) are always applied first. Render thread; fault-isolated.
 */
public final class Mc263Nametags {
	private static final int MODULE = Mods.index(Nametags.ID);
	private static final HookGuard GUARD = new HookGuard("nametags",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	private static boolean allHidden;
	private static boolean playersHidden;
	private static boolean allKeyWas;
	private static boolean playersKeyWas;
	/** What Minecraft's living-entity rules said last (a mob's renderer asks them first). */
	private static boolean lastLiving;

	private Mc263Nametags() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(MODULE);
	}

	/** From the client tick: the toggle keys (only while playing, never while typing). */
	public static void tick() {
		if (!on()) {
			allKeyWas = false;
			playersKeyWas = false;
			return;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			Minecraft mc = Minecraft.getInstance();
			boolean play = mc.player != null && mc.gui.screen() == null;
			boolean all = play && Mc263Features.keyHeld(state.text(Nametags.TOGGLE_ALL));
			if (all && !allKeyWas) {
				allHidden = !allHidden;
				message(state, allHidden ? "Nametags hidden" : "Nametags shown");
			}
			allKeyWas = all;
			boolean players = play && Mc263Features.keyHeld(state.text(Nametags.TOGGLE_PLAYERS));
			if (players && !playersKeyWas) {
				playersHidden = !playersHidden;
				message(state, playersHidden ? "Player nametags hidden" : "Player nametags shown");
			}
			playersKeyWas = players;
			if (state.text(Nametags.TOGGLE_ALL).isEmpty()) {
				allHidden = false;
			}
			if (state.text(Nametags.TOGGLE_PLAYERS).isEmpty()) {
				playersHidden = false;
			}
		} catch (RuntimeException e) {
			GUARD.fail(e);
		}
	}

	private static void message(ModuleState state, String text) {
		if (state.bool(Nametags.TOGGLE_MESSAGE)) {
			Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal(text), false);
		}
	}

	/** A living entity's nametag after Minecraft's rules ({@code vanilla}): hidden while toggled off. */
	public static boolean living(LivingEntity entity, boolean vanilla) {
		lastLiving = vanilla;
		if (!vanilla || !on()) {
			return vanilla;
		}
		return !(allHidden || playersHidden && entity instanceof Player);
	}

	/** A mob's nametag ({@code vanilla}: looked at, or always shown): named mobs and pets always, when asked. */
	public static boolean mob(Entity entity, boolean vanilla) {
		if (vanilla || !on() || !lastLiving || allHidden || !entity.hasCustomName()) {
			return vanilla;
		}
		ModuleState state = Mods.state(MODULE);
		if (state.bool(Nametags.ALWAYS_NAMED)) {
			return true;
		}
		return state.bool(Nametags.ALWAYS_TAMED) && entity instanceof TamableAnimal pet && pet.isTame();
	}

	/** A nametag's text: ping after a player's name, your own in your colour. */
	public static Component text(Entity entity, Component name) {
		if (!on()) {
			return name;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			Component out = name;
			Minecraft mc = Minecraft.getInstance();
			if (entity == mc.player && state.bool(Nametags.OWN_COLOR)) {
				out = out.copy().withColor(state.rgb(Nametags.OWN_RGB));
			}
			if (entity instanceof Player player && state.bool(Nametags.PING) && mc.getSingleplayerServer() == null) {
				ClientPacketListener connection = mc.getConnection();
				PlayerInfo info = connection == null ? null : connection.getPlayerInfo(player.getUUID());
				if (info != null) {
					out = out.copy().append(Component.literal(" " + info.getLatency() + "ms").withStyle(ChatFormatting.GRAY));
				}
			}
			return out;
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return name;
		}
	}

	/** The nametag's size factor (1: Minecraft's). */
	public static float size() {
		return on() ? Mods.state(MODULE).integer(Nametags.SIZE) / 100F : 1F;
	}

	/** One nametag text part, as Minecraft would make it, restyled: shadow, text opacity and background. */
	public static TextFeatureRenderer.Submit part(Matrix4f pose, float x, float y, FormattedCharSequence name, int light, int color, int background,
		Font.DisplayMode mode, Factory vanilla) {
		if (!on()) {
			return vanilla.make(pose, x, y, name, light, color, background, mode);
		}
		try {
			ModuleState state = Mods.state(MODULE);
			float textAlpha = state.integer(Nametags.OPACITY) / 100F;
			int text = ARGB.color(Math.round(ARGB.alpha(color) * textAlpha), color);
			// Minecraft's see-through pass carries the background; the first, depth-tested pass of a visible tag has none.
			int bg = background == 0 && mode == Font.DisplayMode.NORMAL && color == -1 ? 0
				: state.bool(Nametags.BACKGROUND)
					? ARGB.color(Math.round(255 * state.integer(Nametags.BACKGROUND_OPACITY) / 100F), state.rgb(Nametags.BACKGROUND_COLOR)) : 0;
			boolean shadow = state.bool(Nametags.SHADOW) && mode != Font.DisplayMode.SEE_THROUGH;
			TextFeatureRenderer.Content content = new TextFeatureRenderer.Content.Text(x, y, name, shadow, text, bg, 0);
			return new TextFeatureRenderer.Submit(pose, mode, light, content);
		} catch (RuntimeException e) {
			GUARD.fail(e);
			return vanilla.make(pose, x, y, name, light, color, background, mode);
		}
	}

	/** Minecraft's own nametag part maker. */
	@FunctionalInterface
	public interface Factory {
		TextFeatureRenderer.Submit make(Matrix4f pose, float x, float y, FormattedCharSequence name, int light, int color, int background,
			Font.DisplayMode mode);
	}
}
