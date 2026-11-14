package com.flintclient.client.cosmetics;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Store's cosmetic kinds with a 3D model (the cosmetics contract, website's answer): each worn in one slot of the per-player
 * lookup, hanging from a player part unless its geometry names another ({@link CosmeticModel}). Capes and badges stay as they
 * were; an emote isn't worn (it is played: {@link EmotePose}). Pure Java.
 */
public enum CosmeticKind {
	WINGS("wings", "back", CosmeticModel.BODY),
	HEADWEAR("headwear", "head", CosmeticModel.HEAD),
	BODYWEAR("bodywear", "body", CosmeticModel.BODY),
	AURA("aura", "aura", CosmeticModel.BODY),
	PET("pet", "pet", CosmeticModel.BODY),
	SHIELD("shield", "shield", CosmeticModel.BODY),
	GLOVES("gloves", "hands", CosmeticModel.RIGHT_ARM),
	EMOTE("emote", "", CosmeticModel.BODY);

	/** The worn kinds, in the order they are drawn. */
	public static final CosmeticKind[] WORN = {WINGS, BODYWEAR, HEADWEAR, GLOVES, PET, AURA, SHIELD};

	public final String id;
	/** The lookup's slot ("" for an emote). */
	public final String slot;
	public final int defaultAnchor;

	CosmeticKind(String id, String slot, int defaultAnchor) {
		this.id = id;
		this.slot = slot;
		this.defaultAnchor = defaultAnchor;
	}

	public static @Nullable CosmeticKind of(@Nullable String kind) {
		if (kind == null) {
			return null;
		}
		String k = kind.toLowerCase(Locale.ROOT);
		for (CosmeticKind c : values()) {
			if (c.id.equals(k)) {
				return c;
			}
		}
		return null;
	}

	/** The kind worn in {@code slot}, or null. */
	public static @Nullable CosmeticKind ofSlot(String slot) {
		for (CosmeticKind c : WORN) {
			if (c.slot.equals(slot)) {
				return c;
			}
		}
		return null;
	}

	/** The anchor named in the Store's {@code attach.anchor}, or -1. */
	public static int anchor(@Nullable String name) {
		return name == null ? -1 : CosmeticModel.anchorNamed(name);
	}
}
