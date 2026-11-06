/*
 * Based on TierTagger (https://github.com/mctiers-dev/TierTagger), Copyright (c) 2025 MCTiers, mctiers.com.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.flintclient.client.control.platform;

import com.flintclient.client.control.feature.Mods;
import com.flintclient.client.control.feature.tiers.TierLists;
import com.flintclient.client.control.feature.tiers.TierLists.Named;
import com.flintclient.client.control.feature.tiers.TierLists.Profile;
import com.flintclient.client.control.feature.tiers.TierLists.TierList;
import com.flintclient.client.control.model.ModuleState;
import com.flintclient.client.control.model.mods.PlayerMods.TierTagger;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerSkinWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * Tier Tagger's player search: type a name, see that player's profile on the chosen tier list (their skin, region, points,
 * global rank and every gamemode's tier, best first, with when it was reached). Opened from Tier Tagger's page.
 */
public final class TierSearchScreen extends Screen {
	private static final Map<String, Integer> REGIONS = Map.of("NA", 0xFF6A6E, "EU", 0x6AFF6E, "SA", 0xFF9900, "AU", 0xF6B26B, "ME", 0xFFD966, "AS",
		0xC27BA0, "AF", 0x674EA7);
	private final @Nullable Screen parent;
	private @Nullable EditBox name;
	private @Nullable Button search;
	private @Nullable CompletableFuture<?> pending;
	private @Nullable Profile found;
	private @Nullable PlayerSkinWidget skin;
	private Component status = Component.empty();

	public TierSearchScreen(@Nullable Screen parent) {
		super(Component.literal("Player Search"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (found != null) {
			initProfile(found);
			return;
		}
		String typed = name == null ? "" : name.getValue();
		name = new EditBox(font, width / 2 - 100, height / 4 + 60, 200, 20, Component.literal("Username"));
		name.setMaxLength(16);
		name.setValue(typed);
		EditBox box = name;
		// Only what a Minecraft name can hold.
		box.setResponder(s -> {
			String clean = s.replaceAll("[^A-Za-z0-9_]", "");
			if (!clean.equals(s)) {
				box.setValue(clean);
			}
		});
		name.setHint(Component.literal("Username").withStyle(ChatFormatting.DARK_GRAY));
		addRenderableWidget(name);
		search = addRenderableWidget(Button.builder(Component.literal("Search"), b -> search()).bounds(width / 2 - 100, height / 4 + 96, 200, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 - 100, height / 4 + 120, 200, 20).build());
		setInitialFocus(name);
	}

	@Override
	public void tick() {
		super.tick();
		if (search != null && name != null) {
			search.active = pending == null && !name.getValue().isEmpty();
		}
	}

	private void search() {
		if (name == null || pending != null) {
			return;
		}
		String query = name.getValue();
		ModuleState state = Mods.state(Mods.index(TierTagger.ID));
		TierLists.use(TierList.values()[state.option(TierTagger.LIST)]);
		status = Component.literal("Loading...").withStyle(ChatFormatting.GRAY);
		Minecraft mc = Minecraft.getInstance();
		CompletableFuture<Supplier<PlayerSkin>> skinLookup = CompletableFuture.supplyAsync(() -> {
			GameProfile profile = mc.services().profileResolver().fetchByName(query).orElseGet(() -> new GameProfile(UUID.randomUUID(), query));
			return mc.getSkinManager().createLookup(profile, true);
		});
		pending = TierLists.search(query).thenCombine(skinLookup, (profile, skinFor) -> {
			mc.execute(() -> {
				pending = null;
				found = profile;
				skin = new PlayerSkinWidget(60, 144, mc.getEntityModels(), skinFor);
				rebuildWidgets();
			});
			return profile;
		}).exceptionally(t -> {
			mc.execute(() -> {
				pending = null;
				status = Component.literal("Unknown player on " + TierLists.list().title()).withStyle(ChatFormatting.RED);
			});
			return null;
		});
	}

	private void initProfile(Profile profile) {
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> {
			found = null;
			skin = null;
			status = Component.empty();
			rebuildWidgets();
		}).bounds(width / 2 - 100, height - 27, 98, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 2, height - 27, 98, 20).build());
		if (skin != null) {
			skin.setPosition(width / 2 - 65, (height - 144) / 2);
			addRenderableWidget(skin);
		}
		ModuleState state = Mods.state(Mods.index(TierTagger.ID));
		List<Named> tiers = sorted(profile);
		int y = top(tiers.size()) + 56;
		for (Named tier : tiers) {
			StringWidget line = new StringWidget(line(tier, state), font);
			line.setX(width / 2 + 5);
			line.setY(y);
			String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.ofEpochSecond(tier.ranking().attained()));
			line.setTooltip(Tooltip.create(Component.literal("Attained: " + date + "\nPoints: " + TierLists.points(tier.ranking())).withStyle(ChatFormatting.GRAY)));
			addRenderableWidget(line);
			y += 11;
		}
	}

	private int top(int lines) {
		return (height - 56 - lines * 11) / 2;
	}

	private static List<Named> sorted(Profile profile) {
		List<Named> out = new ArrayList<>();
		profile.rankings().forEach((mode, ranking) -> {
			if (mode != null && ranking != null) {
				out.add(new Named(mode, ranking));
			}
		});
		out.sort(Comparator.comparing((Named n) -> n.ranking().retired()).thenComparingInt(n -> n.ranking().tier()).thenComparingInt(n -> n.ranking().pos()));
		return out;
	}

	private static Component line(Named tier, ModuleState state) {
		Component mode = Mc263Tiers.icon(tier.mode()).map(i -> (Component) Component.empty().append(i).append(" "))
			.orElse(Component.literal("• ")).copy().append(Component.literal(title(tier.mode())));
		Component text = Mc263Tiers.tierText(tier.ranking(), state).copy();
		if (!tier.ranking().retired() && tier.ranking().comparablePeak() < tier.ranking().comparableTier()) {
			text = text.copy().append(Component.literal(" (peak: ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(TierLists.label(tier.ranking().peakTier(), tier.ranking().peakPos(), false)))
				.append(Component.literal(")").withStyle(ChatFormatting.GRAY));
		}
		return Component.empty().append(mode).append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(text);
	}

	private static String title(String mode) {
		for (TierLists.Mode m : TierLists.modes()) {
			if (m.id().equals(mode)) {
				return m.title();
			}
		}
		int at = TierTagger.MODE.indexOf(mode);
		if (at >= 0) {
			return TierTagger.MODE.labels().get(at);
		}
		at = TierTagger.SUB_MODE.indexOf(mode);
		return at >= 0 ? TierTagger.SUB_MODE.labels().get(at) : mode;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		Profile profile = found;
		if (profile == null) {
			graphics.centeredText(font, Component.literal("Player Search · " + TierLists.list().title()), width / 2, 20, 0xFFFFFFFF);
			graphics.centeredText(font, status, width / 2, height / 4 + 84, 0xFFFFFFFF);
			return;
		}
		graphics.centeredText(font, Component.literal(profile.name() + "'s profile"), width / 2, 20, 0xFFFFFFFF);
		int y = top(profile.rankings().size());
		String region = profile.region() == null ? "?" : profile.region();
		graphics.text(font, Component.literal("Region: ").append(Component.literal(region)
			.withColor(REGIONS.getOrDefault(region.toUpperCase(Locale.ROOT), 0xFFFFFF))), width / 2 + 5, y, 0xFFFFFFFF);
		graphics.text(font, Component.literal("Points: " + profile.points()), width / 2 + 5, y + 15, 0xFFFFFFFF);
		graphics.text(font, Component.literal("Global rank: #" + profile.overall()), width / 2 + 5, y + 30, 0xFFFFFFFF);
		graphics.text(font, Component.literal("Rankings:"), width / 2 + 5, y + 45, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		if (pending != null) {
			pending.cancel(true);
		}
		Minecraft.getInstance().gui.setScreen(parent);
	}
}
