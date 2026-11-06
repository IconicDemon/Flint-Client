package com.flintclient.legacy;

import com.flintclient.client.control.feature.NametagRules;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.scoreboard.team.AbstractTeam;

/**
 * Your own nametag over you in third person on Minecraft 1.8.9 (Nametags' "Nametag in perspective"), by the rules Minecraft
 * applies to another player's nametag, with you as both player and viewer ({@link NametagRules#ownShown}): your team's
 * nametag visibility (a server that draws names itself, with an armour stand above you, sets "never"), invisibility, nothing
 * riding you, F1. Never on your picture in the inventory. A failure leaves Minecraft's answer for the session. Render thread.
 */
public final class OwnNametag {
	/** How many pictures of an entity are being drawn now (the inventory's player): no own nametag on those. */
	private static int pictures;
	private static boolean failed;

	private OwnNametag() {
	}

	/** Around the inventory drawing your picture: {@code drawing} true before, false after. */
	public static void picture(boolean drawing) {
		pictures = Math.max(0, pictures + (drawing ? 1 : -1));
	}

	/** Minecraft's answer for {@code entity}'s nametag ({@code vanilla}), or yours over you in third person. */
	public static boolean shown(LivingEntity entity, boolean vanilla) {
		if (vanilla || failed || !Flint.ready()) {
			return vanilla;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (entity != mc.player || !Flint.modules().nametags.enabled() || !Flint.modules().nametags.own.on()) {
				return vanilla;
			}
			AbstractTeam team = entity.getScoreboardTeam();
			NametagRules.TeamRule rule = team == null ? null : NametagRules.TeamRule.of(team.getNameTagVisibility().name());
			// Sneaking's 32 blocks are Minecraft's to apply after this (renderNameTag), as for everyone.
			return NametagRules.ownShown(true, mc.options.perspective != 0, !Minecraft.isDisplayGui(), pictures == 0, false,
				!entity.isInvisibleTo(mc.player), entity.rider != null, rule, team != null && team.showFriendlyInvisibles());
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			Flint.LOG.warn("Flint: your own nametag failed; Minecraft's rules only for this session", e);
			return vanilla;
		}
	}
}
