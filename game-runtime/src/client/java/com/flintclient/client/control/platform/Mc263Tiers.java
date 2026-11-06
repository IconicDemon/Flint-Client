/*
 * Based on TierTagger (https://github.com/mctiers-dev/TierTagger), Copyright (c) 2025 MCTiers, mctiers.com.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.FlintClient;
import com.flintclient.client.control.ControlState;
import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.tiers.TierLists;
import com.flintclient.client.control.feature.tiers.TierLists.Highest;
import com.flintclient.client.control.feature.tiers.TierLists.Named;
import com.flintclient.client.control.feature.tiers.TierLists.Ranking;
import com.flintclient.client.control.feature.tiers.TierLists.TierList;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.OptionSetting;
import com.flintclient.client.control.model.mods.PlayerMods.TierTagger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;

/**
 * Tier Tagger in the game: a player's tier ("HT3", with its gamemode's icon) before their name on nametags, in the player
 * list and on a server's text-display nametag riding them, as TierTagger shows it; the key that cycles the gamemode.
 * Tiers come from {@link TierLists}, looked up only while the module is on. Render and client thread; fault-isolated.
 */
public final class Mc263Tiers {
	private static final int MODULE = Mods.index(TierTagger.ID);
	private static final HookGuard GUARD = new HookGuard("Tier Tagger",
		(path, error) -> FlintClient.LOGGER.warn("Flint: the {} hook failed; it's off for this session", path, error));
	/** Gamemode icons: TierTagger's pictures as glyphs of Flint's tiers font, in their own colours. */
	private static final Style ICONS = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(FlintClient.MOD_ID, "tiers")))
		.withColor(0xFFFFFF);
	private static final String[] TIERS = {"HT1", "LT1", "HT2", "LT2", "HT3", "LT3", "HT4", "LT4", "HT5", "LT5"};
	/**
	 * Each player's tag as last worked out, for the rankings and settings it was worked out from: nametags and the player
	 * list ask every frame, and the tag only changes when one of those does.
	 */
	private record CachedTag(Optional<Map<String, Ranking>> rankings, ModuleState state, int revision, Optional<Component> tag) {
	}

	private static final Map<UUID, CachedTag> TAGS = new java.util.HashMap<>();
	private static final int MAX_TAGS = 1024;

	/** A server's text-display nametag as last changed, for the display's laid-out text and the tag it was changed for. */
	private record DisplayTag(Display.TextDisplay.CachedInfo from, Optional<Component> tag,
		Display.TextDisplay.@org.jspecify.annotations.Nullable CachedInfo to) {
	}

	private static final Map<Display.TextDisplay, DisplayTag> DISPLAYS = new java.util.WeakHashMap<>();
	private static boolean cycleWas;

	private Mc263Tiers() {
	}

	private static boolean on() {
		return GUARD.on() && Mods.on(MODULE);
	}

	/** From the client tick: the tier list in use and the cycle key (only while playing). */
	public static void tick() {
		if (!on()) {
			cycleWas = false;
			return;
		}
		try {
			ModuleState state = Mods.state(MODULE);
			TierLists.use(TierList.values()[state.option(TierTagger.LIST)]);
			Minecraft mc = Minecraft.getInstance();
			boolean down = mc.player != null && mc.gui.screen() == null && Mc263Features.keyHeld(state.text(TierTagger.CYCLE));
			if (down && !cycleWas) {
				cycle(state);
			}
			cycleWas = down;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
		}
	}

	private static void cycle(ModuleState state) {
		OptionSetting setting = modeSetting(state);
		int next = (state.option(setting) + 1) % setting.size();
		if (state.set(setting, next)) {
			ControlState.settings().recordChange(TierTagger.ID);
		}
		String id = setting.keys().get(next);
		com.flintclient.client.compat.McCompat.hud().setOverlayMessage(Component.literal("Displayed gamemode: ").append(icon(id).map(i -> (Component) Component.empty().append(i).append(" ")).orElse(Component.empty()))
			.append(Component.literal(setting.labels().get(next)).withStyle(ChatFormatting.WHITE)), false);
	}

	private static OptionSetting modeSetting(ModuleState state) {
		return state.option(TierTagger.LIST) == 1 ? TierTagger.SUB_MODE : TierTagger.MODE;
	}

	/** A player's nametag text with their tier before it, when it shows on nametags. */
	public static Component nametag(Player player, Component name) {
		if (!on() || !Mods.state(MODULE).bool(TierTagger.NAMETAG)) {
			return name;
		}
		return withTier(player.getUUID(), name);
	}

	/** A player-list name with their tier before it, when it shows in the player list. */
	public static Component playerList(UUID player, Component name) {
		if (!on() || !Mods.state(MODULE).bool(TierTagger.PLAYER_LIST)) {
			return name;
		}
		return withTier(player, name);
	}

	private static Component withTier(UUID player, Component name) {
		try {
			Optional<Component> tag = tag(player);
			return tag.isEmpty() ? name : Component.empty().append(tag.get()).append(Component.literal(" | ").withStyle(ChatFormatting.GRAY)).append(name);
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return name;
		}
	}

	/** The tier tag for {@code player} (icon and/or tier as Display type says), or empty while unknown or unranked. */
	public static Optional<Component> tag(UUID player) {
		Optional<Map<String, Ranking>> rankings = TierLists.rankings(player);
		if (rankings.isEmpty()) {
			return Optional.empty();
		}
		ModuleState state = Mods.state(MODULE);
		CachedTag cached = TAGS.get(player);
		if (cached != null && cached.rankings() == rankings && cached.state() == state && cached.revision() == state.revision()) {
			return cached.tag();
		}
		Optional<Component> tag = tag(rankings.get(), state);
		if (TAGS.size() >= MAX_TAGS) {
			TAGS.clear();
		}
		TAGS.put(player, new CachedTag(rankings, state, state.revision(), tag));
		return tag;
	}

	private static Optional<Component> tag(Map<String, Ranking> rankings, ModuleState state) {
		Map<String, Ranking> shown = rankings;
		if (!state.bool(TierTagger.RETIRED)) {
			shown = new java.util.HashMap<>(shown);
			shown.values().removeIf(Ranking::retired);
		}
		OptionSetting modes = modeSetting(state);
		Highest highest = Highest.values()[state.option(TierTagger.HIGHEST)];
		Optional<Named> named = TierLists.shown(shown, modes.keys().get(state.option(modes)), highest);
		if (named.isEmpty()) {
			return Optional.empty();
		}
		Component tier = tierText(named.get().ranking(), state);
		Optional<Component> icon = icon(named.get().mode());
		int display = state.option(TierTagger.DISPLAY);
		if (display == 2 || icon.isEmpty()) {
			return Optional.of(tier);
		}
		return Optional.of(display == 1 ? icon.get() : Component.empty().append(icon.get()).append(tier));
	}

	/** "HT3" in its colour (a retired player's peak as "RHT2", in the retired colour). */
	public static Component tierText(Ranking ranking, ModuleState state) {
		String label = TierLists.label(ranking);
		return Component.literal(label).withColor(color(label, state));
	}

	private static int color(String label, ModuleState state) {
		if (label.startsWith("R")) {
			return state.rgb(TierTagger.RETIRED_COLOR);
		}
		for (int i = 0; i < TIERS.length; i++) {
			if (TIERS[i].equals(label)) {
				return state.rgb(TierTagger.COLORS[i]);
			}
		}
		return 0xD3D3D3;
	}

	/** A gamemode's icon (TierTagger's), if it has one. */
	public static Optional<Component> icon(String mode) {
		char glyph = switch (mode) {
			case "axe" -> '';
			case "mace" -> '';
			case "nethop", "neth_pot" -> '';
			case "pot" -> '';
			case "smp" -> '';
			case "sword" -> '';
			case "uhc" -> '';
			case "vanilla" -> '';
			case "bed" -> '';
			case "bow" -> '';
			case "creeper" -> '';
			case "debuff" -> '';
			case "dia_crystal" -> '';
			case "dia_smp" -> '';
			case "elytra" -> '';
			case "manhunt" -> '';
			case "minecart" -> '';
			case "og_vanilla" -> '';
			case "speed" -> '';
			case "trident" -> '';
			default -> 0;
		};
		return glyph == 0 ? Optional.empty() : Optional.of(Component.literal(String.valueOf(glyph)).withStyle(ICONS));
	}

	/**
	 * A server's text-display nametag riding a player: the line with their name gets their tier before it, and the lines'
	 * widths follow, so the background and centring stay right. Null leaves the display as it is.
	 */
	public static Display.TextDisplay.@org.jspecify.annotations.Nullable CachedInfo textDisplay(Display.TextDisplay entity,
		Display.TextDisplay.@org.jspecify.annotations.Nullable CachedInfo info) {
		if (info == null || !on() || !Mods.state(MODULE).bool(TierTagger.NAMETAG) || !(entity.getVehicle() instanceof Player player)) {
			return null;
		}
		try {
			// Worked out again only when the display's text or the player's tag changed, not every frame.
			Optional<Component> tag = tag(player.getUUID());
			DisplayTag cached = DISPLAYS.get(entity);
			if (cached != null && cached.from() == info && cached.tag() == tag) {
				return cached.to();
			}
			Display.TextDisplay.CachedInfo changed = withTierLine(info, player);
			DISPLAYS.put(entity, new DisplayTag(info, tag, changed));
			return changed;
		} catch (RuntimeException | LinkageError e) {
			GUARD.fail(e);
			return null;
		}
	}

	private static Display.TextDisplay.@org.jspecify.annotations.Nullable CachedInfo withTierLine(Display.TextDisplay.CachedInfo info,
		Player player) {
		List<Display.TextDisplay.CachedLine> lines = info.lines();
		String name = player.getScoreboardName();
		for (int i = 0; i < lines.size(); i++) {
			Component text = styled(lines.get(i).contents());
			String plain = text.getString();
			if (plain.isBlank() || !plain.contains(name)) {
				continue;
			}
			Component with = withTier(player.getUUID(), text);
			if (with == text) {
				return null;
			}
			List<Display.TextDisplay.CachedLine> changed = new ArrayList<>(lines);
			changed.set(i, new Display.TextDisplay.CachedLine(with.getVisualOrderText(), Minecraft.getInstance().font.width(with)));
			int width = changed.stream().mapToInt(Display.TextDisplay.CachedLine::width).max().orElse(info.width());
			return new Display.TextDisplay.CachedInfo(changed, width);
		}
		return null;
	}

	/** A laid-out line back as a component, its styles kept (runs of one style become one part). */
	private static Component styled(FormattedCharSequence sequence) {
		MutableComponent out = Component.empty();
		StringBuilder run = new StringBuilder();
		Style[] current = {Style.EMPTY};
		sequence.accept((index, style, codePoint) -> {
			if (!style.equals(current[0]) && !run.isEmpty()) {
				out.append(Component.literal(run.toString()).withStyle(current[0]));
				run.setLength(0);
			}
			current[0] = style;
			run.appendCodePoint(codePoint);
			return true;
		});
		if (!run.isEmpty()) {
			out.append(Component.literal(run.toString()).withStyle(current[0]));
		}
		return out;
	}
}
